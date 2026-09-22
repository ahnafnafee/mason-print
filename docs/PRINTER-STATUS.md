# Released jobs, charges and cancellation

Open **Released jobs and charges** from the queue to see releases made in this app. Each receipt records the document, selected printer, request time and release acknowledgement. It is saved before sending, so a lost response stays **Release unconfirmed**. The latest 100 receipts are encrypted when saved, scoped to the account and server, and cleared on sign-out. **Clear receipts** removes local history only.

## What a receipt can tell you

**Release accepted** means Pharos acknowledged the release request. **Print charge found** means a unique matching `Print` transaction was found in your statement. The receipt shows its amount, page count and `ChargedTo` funding source when supplied. A transaction proves billing, not physical output: GMU's charges-only release test produced a Print transaction without printing a page.

Use **View statement** for the full billing history, including other print methods. Refresh on the receipts screen checks recent charges again. A missing or ambiguous match remains unconfirmed; it does not mean the release failed or was free.

GMU starts sending a job to the printer immediately after release. In the supplied September 2026 live evidence, released jobs disappeared from every user queue view within a second. The old job resource still returned a stale `Queued` state. Consequently, receipts never read a subsequent print state from that resource or infer completion from an empty queue. The API's user queue filters expose only `Queued` and `Held`, not a reliable post-release pending queue.

## Try to cancel

Choose **Try to cancel** on the receipt and confirm the document and printer. The app sends `DELETE /printjobs` with `{"PrintJobs":[{"Location":"<original job location>"}]}` through the signed-in Pharos connection. It does not guess a printer job number from the document name or transaction. Only the newest receipt for the same original location can attempt cancellation.

The receipt keeps the server's result:

- **Cancellation accepted:** the requested job received an explicit success response. Already printed pages cannot be recalled, and a refund is not guaranteed; check your statement.
- **Cancellation refused:** the server returned a per-job refusal. Its sentence is shown with HTML entities decoded. GMU's live response reported that the Secure Release `DeleteJob` operation failed because the job was already being printed.
- **Cancellation unconfirmed:** no clear acknowledgement for that job arrived. The app does not automatically retry. Check at the printer before deliberately trying again.

HTTP 200 alone is not success. GMU returns per-job failures with `Status:300` and `ErrorCode:TranslationNotFound` inside a successful HTTP response. An SRS “does not exist in the database” response is also a refusal, not proof of cancellation. Cancellation uses a one-shot body with transport retries and redirects disabled to prevent automatic replay, including OkHttp's response-driven `503 Retry-After: 0` retry.

## How charge checks are bounded

While the app is visible, a new receipt starts up to five checks with a one-minute overall limit. Checks stop when recent receipts have matches, when they are more than two minutes old, or when the foreground session ends. Opening the receipts screen or tapping refresh permits a new manual check. One check fetches at most three pages and keeps at most 300 recent transactions, requesting descending transaction identifiers. Short pages advance by their actual length; repeated or empty pages stop the read. A failed read retains previously matched evidence.

Matching requires a Print transaction identifier, a document name match, and a timestamp from five seconds before to two minutes after the release request. If the ledger publishes a printer or device, it must match the selected printer. Both the receipt and transaction must have exactly one candidate; repeated names and competing charges remain unmatched. Existing matches are retained and cannot be reused. This is a billing correlation, not a server-provided release identifier.

The parser accepts ISO timestamps and legacy `M/d/yyyy HH:mm:ss` values. Explicit offsets are honored. Time values without an offset follow the app's existing UTC convention; a differently configured server or an inaccurate phone clock can prevent a match. Missing or invalid times remain unmatched rather than broadening the window.

## Why direct printer monitoring was removed

GMU's supplied device inventory had null `IPv4Address` values for all 302 devices. More importantly, post-release jobs are managed by Pharos Secure Release, and an IPP request for the phone's own jobs cannot identify jobs submitted by Pharos. The app therefore no longer asks for printer addresses, usernames or passwords and removes legacy saved connection settings on upgrade. Administrative `/printjobs` and `/spoolqueues` views require privileges an ordinary GMU account does not have.

Foreground classic SignalR `notificationHub` events still refresh the ordinary account queue. They do not provide physical-completion status, and manual refresh remains available.

## Evidence and regression coverage

This change follows the supplied GMU API 4.11.24.1 live findings in `PHAROS-API-FINDINGS.md` §11 from the companion research project. The physical-printer cancellation response is retained with identifiers sanitized in [released-cancel-refused.json](../app/src/test/resources/gmu/released-cancel-refused.json). Tests exercise that response, both ledger time formats, ambiguous charge matches, page limits, and local HTTP cancellation behavior. The revised app flow is validated with fixtures and local simulations; these checks do not release, cancel or bill real campus jobs.
