package dev.ahnafnafee.masonprint.core

import dev.ahnafnafee.masonprint.data.model.Device
import dev.ahnafnafee.masonprint.data.model.FinishingPayload
import dev.ahnafnafee.masonprint.data.model.PrintJob
import dev.ahnafnafee.masonprint.data.model.encode
import dev.ahnafnafee.masonprint.data.model.putStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The three request bodies that carry money, built exactly as GMU's own Print Center bundle builds
 * them (captured in `pharos/research/webartifact/script.min.js`: `getAdditionalUploadData`,
 * `buildJobUpdateRequestData`, and the `release` action of the job-selection view).
 *
 * Why hand-built rather than serialised from a data class: the server's contract is *shape-
 * sensitive* and inconsistent between endpoints. Numbers in `FinishingOptions` must be JSON
 * strings (`PagesPerSide`/`Copies` as integers ⇒ HTTP 500 at GMU 4.11.24.1), the upload part wants
 * `PrinterName` while the update body wants `Device`, and `CostCenterCode` is written as `""`
 * rather than omitted when the selection agrees on a cost centre. Those are the vendor's quirks,
 * reproduced here on purpose — "cleaning" them is what makes a third-party client fail.
 */
object PrintRequests {

    /**
     * The `MetaData` part of the upload.
     *
     * The Xamarin app sends only `{"FinishingOptions":{…}}` (that is literally its whole
     * `PrintJobUploadFinishingOptions` class); GMU's browser client additionally sends
     * `PrinterName`, the selected device's `Location`. Sending it is what lets the clone pick a
     * printer — something the stock phone app never offered, so the queue defaulted silently
     * (docs/FINDINGS.md §14 U6). Omitted when no device is selected, which is the stock app's
     * behaviour and is accepted.
     */
    fun metaData(finishing: FinishingPayload, printerLocation: String?): String =
        buildJsonObject {
            put("FinishingOptions", finishing.forUpload())
            putStr("PrinterName", printerLocation)
        }.encode()

    /**
     * `POST /printjobs/cost` and `PATCH /printjobs/` share this body — the bundle literally uses
     * one builder for both, so a cost preview and the change that follows cannot disagree.
     *
     * The shared `FinishingOptions`/`CostCenterCode` keys are emitted only when every selected job
     * agrees (the bundle's `every(...)` guards); when they disagree, the per-job value is sent
     * instead. That distinction is preserved because the server applies the top-level values to the
     * whole request.
     */
    fun update(
        jobs: List<PrintJob>,
        finishing: FinishingPayload?,
        deviceLocation: String?,
        costCenterCode: String?,
    ): String = buildJsonObject {
        val shared = jobs.mapNotNull { it.finishing }.distinct().size == 1
        // Present even when empty: the bundle writes `CostCenterCode: pendingCostCenter || ""`, so
        // "" is its way of saying "charge this to the owner's own balance" — omitting the key is a
        // different statement (leave the funding alone) and this API distinguishes them.
        put("CostCenterCode", costCenterCode.orEmpty())
        putStr("Device", deviceLocation)
        if (shared && finishing != null) put("FinishingOptions", finishing.forJobUpdate())
        put(
            "PrintJobs",
            JsonArray(
                jobs.map { job ->
                    buildJsonObject {
                        put("Location", job.location)
                        putStr("CostCenterCode", costCenterCode ?: job.costCenterCode)
                        if (!shared && finishing != null) put("FinishingOptions", finishing.forJobUpdate())
                    }
                },
            ),
        )
    }.encode()

    /**
     * `POST /printjobs/release` → `{PrintJobs, Device, CardId}`.
     *
     * `Device` is the device `Location` the job is being released *at*, and `CardId` is the user's
     * card id or `""` — both always present, because the bundle always sends both keys. Omitting
     * `CardId` entirely is not equivalent to sending it empty on every deployment.
     *
     * `costCenterCode` overrides the funding source for every job in the call. Null keeps whatever
     * the job already carried, which is what a plain "release at this printer" does — the point of
     * the parameter is that *this* is where a department charge becomes real, because the debit
     * happens here and nowhere else (docs/FINDINGS.md §12).
     *
     * `Password` is included per job when the job is protected (`ProtectedBy != "None"`), which is
     * the "release a job from a phone, but prove you own it" path. `ChargeOwner` is deliberately
     * left out: the bundle writes it exactly once in 2.4 MB, only from a client-only flag
     * (`ClientNotPendingCharged`) whose meaning on a student account — release someone else's job
     * to *your* department? — is not worth guessing at. See docs/FUNDING-MODELS.md §2.2 and its
     * open probes.
     */
    fun release(
        jobs: List<PrintJob>,
        deviceLocation: String?,
        cardId: String?,
        passwords: Map<String, String> = emptyMap(),
        costCenterCode: String? = null,
    ): String = buildJsonObject {
        put(
            "PrintJobs",
            JsonArray(
                jobs.map { job ->
                    buildJsonObject {
                        put("Location", job.location)
                        // The bundle always writes this key, as `{}` when the job carries nothing.
                        put("FinishingOptions", job.finishing?.let { FinishingPayload.from(it).forJobUpdate() } ?: emptyObject())
                        /*
                         * `Owner` and `CostCenterCode` are mutually exclusive on this endpoint, and
                         * the empty code is *omitted* rather than sent empty:
                         *
                         *   ClientPendingOwner ? t.Owner = ClientPendingOwner
                         *     : ClientPendingCostCenterCode && (t.CostCenterCode = …)
                         *
                         * (`script.min.js:1@2416520`). Sending `CostCenterCode: ""` is correct on
                         * PATCH/cost, where it means "back to my purse", and is not what the release
                         * body does — docs/FUNDING-MODELS.md §2.2. `Owner` wins when both are known,
                         * which is the "release someone else's job to their department" case.
                         */
                        val code = (costCenterCode ?: job.costCenterCode)?.trim().orEmpty()
                        val owner = job.owner?.trim().orEmpty()
                        when {
                            owner.isNotEmpty() -> put("Owner", owner)
                            code.isNotEmpty() -> put("CostCenterCode", code)
                        }
                        val password = passwords[job.location]
                        if (job.needsPassword && !password.isNullOrBlank()) put("Password", password)
                    }
                },
            ),
        )
        // `Device` is always present and may be JSON *null* (the bundle sends null for a
        // charges-only release, which is not the same as omitting the key or sending "").
        put("Device", deviceLocation)
        put("CardId", cardId.orEmpty())
    }.encode()

    /** `DELETE /printjobs` — a DELETE with a body, same `{PrintJobs:[{Location}]}` envelope. */
    fun delete(jobs: List<PrintJob>): String = buildJsonObject {
        put("PrintJobs", JsonArray(jobs.map { buildJsonObject { put("Location", it.location) } }))
    }.encode()

    private fun emptyObject(): JsonObject = buildJsonObject { }

    /** A device chosen by the user, or the QR payload already resolved to one. */
    fun deviceLocation(device: Device?): String? = device?.location
}
