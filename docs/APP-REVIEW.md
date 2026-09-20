# App review — 19 September 2026

The review covered navigation, session and network state, uploads, print requests, persistence, and every screen in the route table. The visual changes retain the Mason palette, DM Sans typography, green primary actions, and gold funding indicators. Blue identifies colour printing, while amber marks a setting fixed by the server; both also have text and icons. Layout changes focus on wrapping, keyboard access, clear action labels, and recoverable errors.

## Changes

- Bulk print edits preserve fields that were not edited and respect each document's supported settings. Mixed selections show mixed values, and save results distinguish server restrictions from failed changes. Queue cards show a compact fixed label on restricted settings, with the explanation available on tap.
- Queue cards separate the document and estimated price, print settings, and a quiet timestamp with an explicit Preview action. Ordinary held status is conveyed by the queue heading. Funding appears once in the shared Pay with banner, explicitly applying to every selected job; old per-job cost-center metadata no longer competes with the current funding choice in the queue or confirmation. Released history retains its recorded cost center.
- Cost requests carry each document's own finishing options and the selected funding source. Confirmation requires a complete, successful quote for the current selection, printer, and funding source. Partial, unrelated, refused, and unknown quotes cannot enable release; a zero-cost quote can.
- All printer-code entry paths choose a printer and open confirmation. Looking up a code no longer releases the oldest job automatically. The scanner is square, startup and decoder failures offer recovery, and code-entry actions wrap.
- Confirmation lists every selected document, shows its finishing settings, distinguishes queue prices from the current estimate, and describes the requested funding source. The queue warns about a negative balance while leaving eligibility to the server.
- Multiple-file shares use Android's URI list and ClipData formats. Incoming documents and codes wait for sign-in, survive activity recreation, and are not replayed from the launch intent. File-provider work runs off the UI thread, and incoming batches wait for current work.
- Picked and shared documents upload directly in the queue, with compact progress and a brief completion count. Files needing attention remain in an expandable notice; skipped oversized files do not shift the displayed upload position.
- Refreshes retain the previously loaded extent of the queue, reconcile selection, deduplicate page results, and stop when pagination makes no progress. Duplicate refresh and release actions are guarded, and refused deletions remain selected.
- Sign-out cancels and joins session work before clearing local state and browser cookies. Cookie matching respects domains and paths, and serialized persistence prevents an earlier queued save from restoring a cleared session. Turning off password retention removes an earlier saved credential; unavailable encryption no longer falls back to saving secrets in plaintext.
- Password visibility resets on backgrounding. Forms account for the keyboard, checkbox labels are tappable, and keyboard submit follows the same validation as the button.
- Preview downloads use account/server-specific hashes and temporary files. PDF pages support pinch zoom up to 4×, double-tap to enlarge/reset, bounded panning, and accessible zoom/Fit controls. Enlarged pages request a sharper render while retaining the four-megapixel bitmap limit. PDF rendering and closing share a lock, rendering stays off the UI thread, and failures show an actionable state.
- Network response bodies are read off the UI thread, ordinary API requests have an overall timeout, and redirected requests cannot forward Pharos' custom authorization header to another origin. Diagnostic logs retain no token prefixes. Saved server addresses retain their scheme and port.
- Notices use full-width action areas; snackbar cancellation cannot dismiss a newer message. The queue header wraps its selection action, and narrow or enlarged-text job cards move details and prices below the title. Transaction failures are distinguished from an empty history, loaded history is labeled as recent, and funding/help copy avoids unsupported promises. The embedded Print Center waits for cookie import and offers retry/back actions on page failures.

## Verification

`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`

The initial review suite contained 259 tests. Regression coverage includes per-document finishing and costing, consistent funding for jobs with and without saved cost centers, unknown and restricted queue settings, quote completeness, cookie domain/path behavior and pending-save cleanup, bounded preview dimensions, zoom anchors and pan limits, credential-header redaction, and server-address round trips. Lint errors are now build failures.

| Flow | Emulator checks |
| --- | --- |
| Connect and sign-in | Light theme, connection probe, 160% text, keyboard access, password hidden after background/resume; no test credentials submitted |
| Queue and print settings | Live read-back after edits; mixed PDF/JPEG settings preserved through a cost request and refresh; redesigned cards checked in light/dark themes, with selection, restriction explanations, Preview, and wrapping at 320 dp and 160% text; both documents left held, with original colour/sides choices restored |
| PDF preview | Pinch to 4×, panning, double-tap zoom/reset, zoom buttons, Fit, and scrolling to page two; controls wrap at 320 dp and 160% text |
| Upload | Picker cancellation returns to the queue; see the follow-up below for upload and failure checks |
| Printer list and filters | Cached/live directory, building and floor controls, selection navigation |
| Typed printer code | Unknown-code state and reachable wrapping actions at 320 dp width and 160% text |
| QR scanner | Camera starts and renders in a square viewfinder |
| Confirmation | Selected documents, finishing settings, server estimate, funding label and release action |
| Account and history | Account controls, appearance choices, recent transaction list and coverage label |
| Cost centers and add funds | Funding choices, scrolling content and full-width notice actions; no payment made |
| Help and diagnostics | Navigation, explanatory content and capability rows |
| Embedded Print Center | Public sign-in page loads; offline main-page failure shows retry and back actions |

Certificate approval and release-result presentation were reviewed in source; release-result formatting is also covered by unit tests. No physical printing, payment, certificate trust change, full SSO login, or live server-session revocation was performed. Shared-file delivery across authentication was reviewed in code; it was not exercised with a new live login. Emulator display, font, keyboard and network settings were restored after testing.

The remaining lint warnings concern dependency/SDK advisories, deprecated APIs, and style suggestions. They were not addressed through an unrelated dependency upgrade.

## Upload flow follow-up — 20 September 2026

Uploading now stays in the print queue. The separate Upload documents destination has been removed: opening files and per-file progress appear above the queue, successful transfers receive a short count notice, and refused or unconfirmed files remain in a dismissible notice with expandable details. Upload failures are kept separately from refresh failures, so a queue refresh cannot erase the affected file list. A lost response asks the user to check the queue before uploading again, and certificate failures retain the existing approval flow.

An upload reserves its state before coroutine dispatch. Incoming shares wait until the active upload finishes, including when another operation clears the general busy indicator; Delete is disabled during other work. The Upload button also has an explicit accessibility label.

Emulator checks used a local HTTP test server and synthetic documents: picker cancellation, one accepted upload, a two-file batch with one acceptance and one refusal, expandable/dismissible error details, and a shared document waiting through an active upload before being submitted exactly once. Opening, progress, and timeout states were also inspected in dark mode at 320 dp with 160% text; the ordinary upload flow was checked in light mode at 360 dp. These checks did not submit documents to GMU or release print jobs. The temporary preview activity and server are excluded from the delivered app.

The updated suite passes 241 tests after removing the obsolete upload-screen tests and consolidating completion-message cases. Batch planning, stop conditions, oversized files, partial completion counts, and the distinction between a completed transfer and an accepted upload remain covered. Final debug and release builds pass; lint reports 0 errors, 39 warnings, and 4 hints.
