# Released jobs, charges and cancellation

Open **Released jobs and charges** from the queue to see release receipts and recent Pharos statement charges. Each receipt records the document, selected printer, request time and release acknowledgement. It is saved before sending, so a lost response stays **Release unconfirmed**. The latest 100 receipts are encrypted when saved, scoped to the account and server, and cleared on sign-out. Recent print charges can still appear after sign-out and sign-in, or when the job was released through another client. **Clear receipts** removes local receipts only.

## What a receipt can tell you

**Release accepted** means Pharos acknowledged the release request. **Print charge found** means a unique matching `Print` transaction was found in your statement. The receipt shows its amount, page count and `ChargedTo` funding source when supplied. A transaction proves billing, not physical output: GMU's charges-only release test produced a Print transaction without printing a page.

Use **View statement** for the full billing history. Refresh on the receipts screen checks recent charges again. A charge with a unique receipt match appears on that receipt. Other print charges appear under **Print charges from your statement**.

GMU starts sending a job to the printer immediately after release. In the supplied September 2026 live evidence, released jobs disappeared from every user queue view within a second. The old job resource still returned a stale `Queued` state. Consequently, receipts never read a subsequent print state from that resource or infer completion from an empty queue. The API's user queue filters expose only `Queued` and `Held`, not a reliable post-release pending queue.

## Try to cancel

Before release, select the jobs in the queue, open **More actions**, and choose **Cancel print job** or **Cancel print jobs**. At a GMU Canon station you can also select the queued job and tap **Delete**. [GMU Self Print Instructions](https://printandmail.gmu.edu/printinstructions/)

After release, open **Released jobs and cancellation** from the result, then choose **Try to cancel** on the receipt. The app sends `DELETE /printjobs` with `{"PrintJobs":[{"Location":"<original job location>"}]}` through the signed-in Pharos connection. The newest receipt for that location carries the cancellation control. Statement rows have billing information and lack a Pharos job location.

The receipt keeps the server's result:

- **Cancellation accepted:** the requested job received an explicit success response. Already printed pages cannot be recalled, and a refund is not guaranteed; check your statement.
- **Cancellation refused:** the server returned a per-job refusal. Its sentence is shown with HTML entities decoded. GMU's live response reported that the Secure Release `DeleteJob` operation failed because the job was already being printed.
- **Cancellation unconfirmed:** no clear acknowledgement for that job arrived. The app does not automatically retry. Check at the printer before deliberately trying again.

GMU can return HTTP 200 with a per-job refusal such as `Status:300` and `ErrorCode:TranslationNotFound`. An SRS “does not exist in the database” response is also a refusal. Each cancellation opens a fresh connection and sends a one-shot body with transport retries and redirects disabled, including OkHttp's response-driven `503 Retry-After: 0` retry.

### Stop the remaining copies at the printer

Open **How to stop at the printer** from the release result or receipt. On the Canon imageRUNNER ADVANCE DX C5800 series, press **Stop** while your job is printing, select the document, and tap **Cancel**. You can also use **Status Monitor → Copy/Print → Job Status**, select your document, and tap **Cancel**. [Canon Stop control](https://oip.manual.canon/USRMA-5488-zz-CS-5800-enUS/contents/devu-basicope-cancel.html) · [Canon Status Monitor guide](https://oip.manual.canon/USRMA-5488-zz-CS-5800-enUS/contents/devu-prt-pc-check_status_log.html)

For a six-copy job, cancel the active document to stop the rest of the job after you have the copy you need. Cancel can stop partway through the next copy. If you only want one proof, release one copy first and hold a separate job for the remaining five until you are ready.

The printer’s access settings can hide jobs or restrict cancellation. If your document is missing or Cancel is unavailable, give the printer name to the staff at the printer or GMU Print Services. [Canon access settings](https://oip.manual.canon/USRMA-5488-zz-CS-5800-enUS/contents/devu-set_reg-mng-dev.html) · [GMU Print Services](https://printandmail.gmu.edu/hours-locations/)

## How charge checks are bounded

While the app is visible, a new receipt starts up to five checks with a one-minute overall limit. Checks stop when recent receipts have matches, when they are more than two minutes old, or when the foreground session ends. Opening Released Jobs or tapping refresh checks the statement even if there are no receipts. One check fetches at most three pages and keeps at most 300 recent transactions, newest first by time. Short pages advance by their actual length, and repeated or empty pages stop the read. A failed read retains the previously loaded charges and receipt matches.

Matching requires a Print transaction identifier, a document name match, and a timestamp from five seconds before to two minutes after the release request. If the ledger publishes a printer or device, it must match the selected printer. Both the receipt and transaction must have exactly one candidate; repeated names and competing charges remain unmatched. Existing matches are retained and cannot be reused. This is a billing correlation, not a server-provided release identifier.

The parser accepts ISO timestamps and legacy `M/d/yyyy HH:mm:ss` values. Explicit offsets are honored. Time values without an offset follow the app's existing UTC convention; a differently configured server or an inaccurate phone clock can prevent a match. Missing or invalid times remain unmatched rather than broadening the window.

## Why direct printer monitoring was removed

GMU's supplied device inventory had null `IPv4Address` values for all 302 devices. More importantly, post-release jobs are managed by Pharos Secure Release, and an IPP request for the phone's own jobs cannot identify jobs submitted by Pharos. The app therefore no longer asks for printer addresses, usernames or passwords and removes legacy saved connection settings on upgrade. Administrative `/printjobs` and `/spoolqueues` views require privileges an ordinary GMU account does not have.

Foreground classic SignalR `notificationHub` events still refresh the ordinary account queue. They do not provide physical-completion status, and manual refresh remains available.

## Evidence and regression coverage

This change follows the supplied GMU API 4.11.24.1 live findings in `PHAROS-API-FINDINGS.md` §11 from the companion research project. The physical-printer cancellation response is retained with identifiers sanitized in [released-cancel-refused.json](../app/src/test/resources/gmu/released-cancel-refused.json). Tests exercise that response, both ledger time formats, ambiguous charge matches, page limits, and local HTTP cancellation behavior. The revised app flow is validated with fixtures and local simulations; these checks do not release, cancel or bill real campus jobs.
