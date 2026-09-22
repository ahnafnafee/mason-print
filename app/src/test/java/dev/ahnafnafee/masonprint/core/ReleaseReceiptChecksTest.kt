package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.*
import dev.ahnafnafee.masonprint.data.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReleaseReceiptChecksTest {
    private val location = "/printjobs/example"
    private fun verdict(body: String) = cancellationVerdict(JobOperationResult.from(body), location, 42)
    private fun txn(id: Int) = Transaction.from("""{"Identifier":"$id"}""".toJsonObject())

    @Test fun `captured SRS refusal decodes entities and never claims cancellation`() {
        val body = requireNotNull(javaClass.getResourceAsStream("/gmu/released-cancel-refused.json"))
            .bufferedReader().use { it.readText() }
        val answer = verdict(body)
        assertEquals(CancelVerdict.Refused, answer.verdict)
        assertEquals("The Secure Release operation 'DeleteJob' failed. The print job '00000000-0000-0000-0000-000000000001' is already being printed.", answer.message)
        assertEquals(42L, answer.attemptedAt)
        assertEquals(1, JobOperationResult.from(body).failures.size)
    }
    @Test fun `only explicit uncontradicted success for exactly one target counts`() {
        assertEquals(CancelVerdict.Accepted, verdict("""[{"Location":"$location","Status":200}]""").verdict)
        for (body in listOf("[]", """[{"Location":"/printjobs/other","Status":200}]""",
            """[{"Location":"$location"}]""", """[{"Location":"$location","Status":200},{"Location":"$location","Status":200}]""")) {
            assertEquals(CancelVerdict.Unconfirmed, verdict(body).verdict)
        }
        for (fields in listOf("\"Status\":300", "\"Status\":200,\"ErrorCode\":\"TranslationNotFound\"",
            "\"Status\":200,\"Success\":false")) {
            assertEquals(CancelVerdict.Refused, verdict("""[{"Location":"$location",$fields}]""").verdict)
        }
    }
    @Test fun `missing secure job is a refusal and blank user message falls back to server detail`() {
        val answer = verdict("""[{"Location":"$location","Status":300,"UserMessage":"","DeveloperMessage":"Secure job does not exist in the database."}]""")
        assertEquals(CancelVerdict.Refused, answer.verdict)
        assertEquals("Secure job does not exist in the database.", answer.message)
        assertEquals(CancelVerdict.Unconfirmed, unconfirmedCancellation(PharosFailure.Unknown(IllegalStateException("lost response")), 42).verdict)
    }
    @Test fun `ledger polling caps requests and rows`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val result = readReleaseTransactions { skip, size ->
            offsets += skip
            assertEquals(100, size)
            ApiResult.Ok(Page((skip until skip + size).map(::txn), 1000, null, "next"), 200)
        } as ApiResult.Ok
        assertEquals(listOf(0, 100, 200), offsets)
        assertEquals(300, result.value.size)
        val oversized = readReleaseTransactions { _, _ -> ApiResult.Ok(Page((0..500).map(::txn), 1000, null, "next"), 200) } as ApiResult.Ok
        assertEquals(300, oversized.value.size)
    }
    @Test fun `short pages advance by returned rows`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val result = readReleaseTransactions { skip, _ ->
            offsets += skip
            ApiResult.Ok(Page((skip until skip + 50).map(::txn), 150, null, "next"), 200)
        } as ApiResult.Ok
        assertEquals(listOf(0, 50, 100), offsets)
        assertEquals(150, result.value.size)
    }
    @Test fun `repeated and empty pages cannot keep polling`() = runBlocking {
        var requests = 0
        val repeated = readReleaseTransactions { _, _ ->
            requests++
            ApiResult.Ok(Page(listOf(txn(1)), 1000, null, "next"), 200)
        } as ApiResult.Ok
        assertEquals(2, requests)
        assertEquals(1, repeated.value.size)
        requests = 0
        readReleaseTransactions { _, _ -> requests++; ApiResult.Ok(Page<Transaction>(emptyList(), 1000, null, "next"), 200) }
        assertEquals(1, requests)
    }
    @Test fun `failed or cancelled polls cannot silently supply incomplete billing evidence`() = runBlocking {
        val error = ApiResult.Err(PharosFailure.NotFound("example"))
        assertSame(error, readReleaseTransactions { skip, _ ->
            if (skip == 0) ApiResult.Ok(Page(listOf(txn(1)), 2, null, "next"), 200) else error
        })
        try {
            readReleaseTransactions { _, _ -> throw CancellationException("signed out") }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
    }
}
