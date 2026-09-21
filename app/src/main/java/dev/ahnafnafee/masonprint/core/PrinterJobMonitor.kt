package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.net.IppClient
import dev.ahnafnafee.masonprint.data.net.IppFailure
import dev.ahnafnafee.masonprint.data.net.PrinterConnection
import dev.ahnafnafee.masonprint.data.net.PrinterJob
import dev.ahnafnafee.masonprint.data.net.PrinterJobSnapshot
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

data class PrinterMonitorState(
    val connection: PrinterConnection? = null,
    val snapshot: PrinterJobSnapshot? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    val notice: String? = null,
    val cancellationRequested: Set<String> = emptySet(),
)

internal fun PrinterJob.identityKey(): String = uuid ?: "$uri:$created:$owner"

/** Screen-scoped credentials and operations; only the non-secret connection is saved by the UI. */
class PrinterJobMonitor(private val client: IppClient = IppClient()) {
    private val _state = MutableStateFlow(PrinterMonitorState())
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var password = ""

    suspend fun connect(connection: PrinterConnection, printerPassword: String): Boolean {
        if (!mutex.tryLock()) return false
        try {
            _state.value = PrinterMonitorState(connection = connection, busy = true)
            password = printerPassword
            val snapshot = client.jobs(connection, password)
            _state.update { it.copy(snapshot = snapshot) }
            return true
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            _state.update { it.copy(error = failureText(error)) }
            return false
        } finally { _state.update { it.copy(busy = false) }; mutex.unlock() }
    }

    suspend fun refresh(clearActionError: Boolean = true) {
        val connection = _state.value.connection ?: return
        if (!mutex.tryLock()) return
        try {
            _state.update { it.copy(busy = true, error = null,
                actionError = if (clearActionError) null else it.actionError) }
            val snapshot = client.jobs(connection, password)
            _state.update { it.copy(snapshot = snapshot,
                cancellationRequested = it.cancellationRequested.intersect(snapshot.jobs.map { job -> job.identityKey() }.toSet())) }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { _state.update { it.copy(error = failureText(error)) }
        } finally { _state.update { it.copy(busy = false) }; mutex.unlock() }
    }

    suspend fun cancel(job: PrinterJob) {
        val before = _state.value
        val connection = before.connection ?: return
        if (before.busy || before.actionError != null || before.snapshot?.canCancel != true || job.identityKey() in before.cancellationRequested) return
        val current = before.snapshot.jobs.firstOrNull { job.isSameJob(it) && it.active }
        if (current == null) {
            _state.update { it.copy(actionError = "This job changed or is no longer active. Refresh the printer jobs.") }
            return
        }
        if (!mutex.tryLock()) return
        var accepted = false
        try {
            _state.update { it.copy(busy = true, error = null, actionError = null, notice = null) }
            client.cancel(connection, password, current)
            accepted = true
            _state.update { it.copy(notice = "The printer accepted the cancellation request.",
                cancellationRequested = it.cancellationRequested + job.identityKey()) }
            val snapshot = client.jobs(connection, password)
            _state.update { it.copy(snapshot = snapshot) }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            _state.update { it.copy(
                error = if (accepted) "Cancellation was accepted, but the latest job status could not be read. Refresh to check." else it.error,
                actionError = if (accepted) null else if (error is IppFailure) error.message
                    else "Cancellation could not be confirmed. Refresh the printer jobs before trying again.",
            ) }
        } finally { _state.update { it.copy(busy = false) }; mutex.unlock() }
    }

    fun forgetPassword() { password = "" }

    private fun failureText(error: Exception): String = when (error) {
        is IllegalArgumentException, is IppFailure -> error.message ?: "The printer could not complete this request."
        is SSLException -> "The printer's secure connection could not be verified. Check its certificate with print support."
        else -> "Could not reach the printer. Check its address and your campus network connection."
    }
}
