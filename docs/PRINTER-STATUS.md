# Released jobs and printer status

The queue's **Released jobs and printer status** link opens receipts for releases made in this app. A receipt records the document, selected printer, time, and whether the server acknowledged the request. It is saved before sending, so a lost response remains **Release unconfirmed**. Receipts survive disappearance from the queue, are limited to the latest 100, and are cleared on sign-out. **Clear receipts** only removes local history; it does not cancel printing.

An accepted Pharos release does not confirm physical output. The app updates the latest receipt when the server explicitly reports a subsequent state, but never infers completion from disappearance. GMU's public Print Center bundle uses classic SignalR 2 `notificationHub` events, including `ReleasedJob`; these events refresh the account queue while the app is visible. They are not a printer completion feed. Manual refresh and upload analysis polling remain available if notifications cannot connect.

## Connect to a printer

1. Open **Check a printer** and select the printer, or use **Check printer jobs** on a receipt.
2. Ask print support for its full `ipp://` or `ipps://` address, including the printer path, and the username under which your jobs arrive. The Pharos device directory does not supply verified IPP endpoints, so the app does not guess addresses from station labels.
3. Enter the address and printer username. If the printer needs a password, use IPPS and enter its separate printer password. The app supports HTTP Basic authentication over IPPS; other authentication schemes require printer-side configuration or another management tool. No Pharos login or cookie is copied to this connection.
4. Tap **Connect**. Successful connections remember the address and username for this account and printer. The password is kept only while the screen is open.

The printer's pending jobs refresh every ten seconds while the screen is visible. Labels distinguish waiting, held, printing, stopped, cancelled, aborted and completed states when reported. An empty result means no matching jobs were exposed by this connection; jobs might have finished, use a different owner name, or be hidden by the printer's policy. A connection failure retains the last successful list and labels it as stale.

## Cancel a job

**Cancel this job** is offered only for an active job whose printer supports both `Get-Job-Attributes` and `Cancel-Job` and reports a valid job UUID. Confirm the document and job number in the dialog. The app then reads fresh printer capabilities and job attributes, verifies the owner, UUID, printer and job identity, and sends a single cancellation request. It never matches a receipt to a device job using the filename.

A printer may reject the request because of permissions or because printing has already finished. A successful response means cancellation was accepted; already printed pages cannot be recalled. If the response is lost, the app reports that cancellation is unconfirmed and requires a fresh status check before another attempt. Printers without stable UUIDs remain viewable but cannot be cancelled here, because job numbers can be reused after a reboot.

## Deployment limits and evidence

GMU's physical printer connectivity, job owner mapping, authentication and cancellation permissions remain unverified. Campus Wi-Fi or an administrator-provided network route may be required. IPPS uses Android's trusted certificate authorities and does not reuse a Pharos certificate exception. Configuring an endpoint does not grant printer permissions. Mock protocol and UI checks exercise the implementation without releasing or cancelling real campus jobs.

The implementation uses `Get-Printer-Attributes`, `Get-Jobs`, `Get-Job-Attributes` and `Cancel-Job` from [RFC 8011](https://www.rfc-editor.org/rfc/rfc8011.html), with the binary attribute encoding defined by [RFC 8010](https://www.rfc-editor.org/rfc/rfc8010.html). Notification behavior was identified in [GMU's deployed Print Center](https://mobileprint.gmu.edu/myprintcenter/) in September 2026. Administrative Pharos queues, EDI, and cloud reporting APIs are not used because their availability and authorization for ordinary GMU accounts have not been established.
