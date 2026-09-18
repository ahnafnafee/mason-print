# Claude Design prompt — Mason Print (native Kotlin + Material 3 Expressive)

The app lives in this repository (`dev.ahnafnafee.masonprint`, single-Activity Jetpack Compose app,
`MaterialExpressiveTheme` already wired in `app/src/main/java/dev/ahnafnafee/masonprint/ui/theme/Theme.kt`).
This file is the brief to hand **Claude Design** to produce the refined, UX-focused UI design for it.

## How to use

1. Open Claude Design from this checkout — in Claude Code: `/design` (run from the repo root),
   or at claude.ai/design with this repo linked so it can read `Theme.kt` and `docs/CLONE-PLAN.md`.
2. Paste everything between the two markers below. Attach any captured screenshots if any
   stock-app captures exist by then; the visual target is *not* the stock app, so skip them if absent.
3. Let it answer its own clarifying questions from the *Ground truth* section — the answers are in there.
4. Iterate with the follow-up prompts at the bottom (directions → a11y pass → dark pass → handoff).

--- BEGIN PROMPT ---

# Design task: Mason Print — campus print queue client for Android

## Goal

Design the complete UI for **Mason Print**, a native Android replacement for the Pharos Print
campus-print app. Everything is Jetpack Compose in **Material 3 Expressive**. Deliver an interactive,
clickable prototype of every screen and every state, in an Android phone frame, plus a component
gallery and a token spec that a Compose developer can implement from without inventing anything.

The app is a real client for the school's print server (`/PharosAPI`, a Pharos Uniprint/Blueprint
deployment; reference host `mobileprint.gmu.edu`, API version `4.11.24.1`). The vendor's app is a
login form stapled to a WebView: it shows no balance, no job costs, no statuses, and after an upload
it tells you to "Continue to Print Center" and finish in a web page. This design's whole job is to
make the money and the queue legible in the app, natively.

## Audience and the moment of use

Primary: a student, standing next to a print release station, one-handed, in a hurry, on campus
Wi-Fi. They are answering four questions in about ten seconds: *am I signed in to the right campus,
how much money do I have and who is paying for it, what is waiting to print, and how do I release it
at this machine.* Secondary: a student whose department pays for their printing (a cost center makes
the same job $0.00), and a service-desk chat where the two people must compare identical screenshots.

Phone-first, portrait, edge-to-edge. Design for a 360×800 dp viewport and say what changes at
foldable/tablet width rather than designing a second app.

## Ground truth (do not contradict, do not invent beyond it)

- **Money moves when a job is released, not when it is uploaded.** So a price must be visible
  *before* release, and it is the trust feature of the app. GMU prices: $0.10 per B/W page,
  $0.25 per color page.
- **There is no in-app wallet and no card entry.** At GMU the server answers "Add Funds: Deny" and
  "Credit Card Gateway: No"; balance goes up through the campus system (Mason Money / CBORD
  "Transfer Funds"). The add-funds affordance must therefore be an honest explanation plus a jump out
  to the campus page — never a fake checkout. Card data may only ever appear on a hosted web page.
- **Almost every capability is server-driven.** The server says whether web upload is allowed, the
  max upload size, whether the camera/QR release is enabled, whether finishing options may be edited,
  whether cost centers are permitted, whether add-funds is permitted, which payment gateway exists,
  and whether login goes through campus SSO (CAS). The UI **hides** what the server disallows and
  explains why on the account screen. Design the hidden states, not just the shown ones.
- **A cost center (department code) changes who pays, and it is attached per job.** This is the whole
  difference between a $2.50 job and a $0.00 one: "free printing" at this campus is a cost center, not
  a discount, and when a job carries one the student's own balance does not move. The server — not the
  app — decides whether a code is valid; the app offers the codes it was granted at sign-in plus a
  live search of the whole code namespace, and results found by search must be visibly labeled as
  such. The account's balance may be split across several **purses**, which the server draws top to
  bottom in its own order. Both facts must be visible.
- **Cost comes back as a number with three meanings** and the design must tell them apart: a positive
  price, `0` = free (charged to a grant), and `-1` = the server refused to price it, which the
  server's own web client treats as *do not release*. A `-1` state that sends a student walking to a
  printer that will refuse them is a design failure.
- **Errors must quote the server.** The server returns its own sentence for "file too large", "file
  type not allowed", "upload disabled"; the app shows that sentence instead of a canned message.
- **TLS is explicit.** The vendor accepted any certificate silently (so a captive portal could render
  a fake login). This app asks once per certificate fingerprint and remembers. That prompt is a
  designed screen, not a system dialog.
- **Offline is a designed state.** Cached job list and last-known balance with a visible age, not an
  error dialog.
- **Logout is real** (the server session is revoked). Sign in can be replaced by a biometric unlock of
  the stored session.
- The list of things the app can know is finite: host/API version, capabilities, user + balance +
  purses + granted cost centers, jobs, a cost preview for a selection, devices/printers, transaction
  history, a cost-center search, an optional rendered page preview. Do not add charts, avatars,
  maps of printers, gamification, or AI features. Every number on screen must be traceable to one of
  those.

## Screens to design

Phone frames, laid out as a navigable flow plus a flat "all screens" board. For each screen, draw its
loading, empty, error, and offline variants where they can occur.

1. **Campus / connection.** Print-server address (paste a URL or a `host:port`), saved campuses with
   a per-campus account, suggested hosts. After probing, the discovered API version and capability
   summary appear as a status chip *before* credentials are typed. Include the first-contact
   certificate prompt (host + SHA-256 fingerprint + why this is asked + trust / not now) and the
   "nothing answered at that address" state.
2. **Sign in.** Username + password, "keep me signed in", a biometric-unlock option, and — when the
   server reports CAS — a "Continue with Mason Login" path that hands off to a browser sheet. Wrong
   password quotes the server. If the server demands MFA the screen says so and routes to the web
   path instead of failing silently.
3. **Queue (home).** The most important screen; give it three genuinely different layout directions
   before committing.
   - A **balance hero**: the amount in expressive display type on a container; per-purse breakdown as
     chips when there is more than one purse; a "Charged to …" line naming the funding source. When a
     cost center is active, this container visibly changes identity — at that moment the student's own
     balance is no longer the number that matters, and the honest headline becomes "$0.00 to me, from
     the department's grant". Include an **arrears** variant (red) and a **stale cached** variant
     (gray, with age).
   - A **job list**: one card per held job with file name, page count split B/W and color, copies,
     duplex, submitted time, expiry, a **status chip** (received / held / released / problem — icon
     and text, never color alone), its **cost**, and — when it carries one — the cost center it will
     be charged to. Selecting jobs raises a bottom action bar: printer, funding source, count, delete,
     estimate, release. A selection can be mixed (some jobs already carry a cost center, some do not)
     and the server only accepts one shared funding choice for the batch, so the design must resolve
     that — make the selection agree, or release job by job — rather than pretend it cannot happen.
   - Pull-to-refresh, "load more" paging, upload progress in place, an explicit release entry point
     (always visible and labeled), and a real empty state.
   - Overflow: refresh, account, diagnostics, log off. Nothing is a hidden long-press.
4. **Send a document / finishing sheet.** Reached from the in-app picker and from another app's share
   sheet. A file card (name, size, type) with the server's own upload limit and a warning chip that
   can be overridden when the extension is not on the allow list; then copies, pages-per-side,
   simplex/duplex, color/B&W, page size, and funding source — with a **live cost readout** that
   updates as options change. Then a streaming upload state with byte progress, cancel, and a
   retry-queued state for a dropped connection. On success, a snackbar that goes to the job.
5. **Release at the printer.** Printer list searchable and grouped by building/location, last-used
   first; a camera viewfinder for the QR code on the machine, with a camera-denied state that jumps
   to app settings; and a numeric keypad for typing the code shown on the station screen. After the
   device resolves: that printer, the held jobs, the total, confirm. Then a per-job result list that
   shows partial failures (three jobs released, one refused, with the server's reason).
6. **Account.** Balance breakdown with the purse spending order explained in one sentence; transaction
   history with the vendor's codes decoded (`TF` campus transfer in, `PA` add funds, `PF` gateway fee,
   `CR` credit); the add-funds branch (GMU: "topped up with Mason Money" + open campus page);
   cost-center search and selection with the live results labeled as found-on-server; default
   finishing options; saved campuses; the certificate trust list with revoke; diagnostics entry; log
   off. Everything the server disallowed appears here as a hidden feature with its reason.
7. **Diagnostics.** Server address and API version, the capability matrix as readable rows, certificate
   fingerprints, this session's log, all selectable and copyable for a support chat.
8. **Print Center (fallback).** The server's own web page inside the app, under a native top bar with a
   back affordance, plus a line explaining that this part is the server's UI.

Cross-cutting components to include in the gallery: top bar, balance hero, job card (default /
selected / released / problem), status chip set, bottom action bar, printer row, transaction row,
bottom sheet, cost-estimate dialog, delete confirm + undo snackbar, snackbars, progress states
(busy bar, indeterminate wait, upload with percentage), key pad, empty/loading/error/offline,
certificate prompt, permission-denied state.

## Material 3 Expressive, specifically

Use `MaterialExpressiveTheme` + `MotionScheme.expressive()`. The expressive vocabulary is load-bearing
here, not decoration: this app has to make state that the old client never showed (which purse, which
department, what it will cost) readable at a glance on a phone held in one hand.

- Buttons render as full stadium pills; large, ≥ 48 dp, primary actions in the bottom third.
- A FAB that **morphs** shape and size with context: extended ("Send document") while the queue is
  the only story, collapsed once a selection owns the screen.
- Selection is expressed by **container color**, not only a checkmark: a selected job becomes its
  container, the chosen funding source becomes its container.
- Shape morphing between a job card and the job's detail sheet, so a detail reads as the same object
  grown, not a new page.
- Expressive loading (the expressive spinner / progress bar), badges, switching buttons or segmented
  buttons for color-vs-B&W and simplex-vs-duplex, chips for purses and finishing options, split
  button where an action has one obvious default and one alternate.
- Motion carries meaning: a cost that just changed moves like an object (spring, slight overshoot);
  a bar that arrived slides in with overshoot. Provide a reduced-motion variant.
- **Color.** Keep the existing brand and extend it — do not invent a new palette. GMU blue
  `#00569E` = primary; marigold `#FFB700` and charcoal `#333F48` are inherited from the Pharos brand.
  Marigold cannot carry white text at an acceptable ratio, so it belongs in containers and accents,
  never as a filled button with white type. Supply the complete tonal set (all `surfaceContainer*`
  tones, outlines, inverse, and a green "released/OK" and amber "held/warning" pair defined as tokens,
  not ad-hoc colors). Design **light and dark as equals**, side by side, for every screen. Dynamic
  color is off by default (so a student's and a support agent's screenshots match) — say so on the
  board.
- **Type.** The Compose Material 3 scale (Roboto Flex variable). The balance in display size; wire
  details, certificate fingerprints, and cost-center codes in monospace. Show the balance at 200 %
  font scale and design what happens instead of clipping.
- **Accessibility, as a gate not a garnish.** TalkBack label and role for every control; the live cost
  readout and upload progress announced as live regions; ≥ 4.5:1 body and ≥ 3:1 large/graphic
  contrast in both themes; no status conveyed by color alone; haptics on release success and failure;
  every tap target ≥ 48 dp; no portrait lock and landscape not broken.

## Voice

Plain, dry, factual, no marketing. Screens should sound like a competent person telling you what the
server said. Use **US English** and the print system's own vocabulary in the UI strings — *cost
center*, *charge to a cost center*, *grant*, *hold*, *release*, *finish*, *purse* — because a student
and a service-desk agent have to be reading the same words. Write the real English strings, in the
artifact, for: sign-in failure, MFA-required, nothing-here-yet, airplane-mode-stale, upload-too-large
(with the server's own sentence and limit), unsupported file type, upload-disabled-by-server,
insufficient balance / arrears, free-because-charged-to-a-grant (`$0.00` explained, not just shown),
cost refused by server (`-1` — the server will not release what it cannot price), release partially
failed, camera denied, certificate untrusted, logged out. Never "Oops!" and never "Something went
wrong".

## Constraints (a design that breaks one of these is unusable)

- Everything must be buildable with today's Compose Material 3 APIs in Kotlin — no iOS idioms, no
  web-only components. For each distinct component, name the Compose API to use (`PullToRefreshBox`,
  `ModalBottomSheet`, `Badge`, `Switch`, `SegmentedButton`, `AssistChip`, `ListItem`,
  `ExposedDropdownMenu`, `FloatingActionButton`, `AnimatedVisibility` + `MotionScheme` specs,
  `ListDetailPaneScaffold` for wide screens, etc.).
- One Activity, one back stack, depth ≤ 2 from the queue. Do not introduce a bottom tab bar; the app
  opens on the queue.
- No card data, no checkout UI, no "free printing" promise anywhere — free is something a department's
  grant does, and the app can only show it.
- No dependency on push notifications or realtime updates as a design crutch: refresh is pull-based
  with a background balance refresh when the app is foregrounded.
- minSdk 26, target SDK 36; respect system bars, IME, and the back gesture.
- **Keep the icon budget honest.** The current debug build is 47.75 MB against a 1.71 MB release
  because of `material-icons-extended`; the release already tree-shrinks it, but a design that needs
  sixty bespoke glyphs will not stay this small. Prefer standard Material Symbols already in the set,
  and call out the handful of custom icons (campus, printer, purse, cost center, QR station) as
  drawn assets.
- The implementation stays dependency-light: hand-written DI graph and hand-rolled JSON, no Hilt, no
  Retrofit. Nothing in the design may require a library the project does not already have.

## What to deliver

1. The clickable prototype: the happy path (connect → sign in → see balance → send a document with a
   cost preview → pick printer → release) plus four failure paths (offline/stale, arrears, upload
   over the server's limit, certificate prompt, camera denied).
2. Three distinct layout directions for the Queue screen, with a short written case for each and one
   recommendation.
3. A component gallery board in both themes with all states of every component listed above.
4. A **token and component spec** readable as a table: color name → hex → `ColorScheme` slot;
   type role → `MaterialTheme.typography` slot; radius → `MaterialTheme.shapes` slot; spacing values;
   motion specs → `MaterialTheme.motionScheme` calls with durations; component → Compose API and its
   Expressive opt-in requirement. It has to be implementable against this existing theme file without
   guesswork: `app/src/main/java/dev/ahnafnafee/masonprint/ui/theme/Theme.kt`.
5. A short rationale, one line per major decision, naming which defect in the current vendor app it
   fixes (its UI shows no balance; job status/cost are only in the web page; reload is a hidden
   long-press; the QR scanner is unlabeled and server-hidden; uploads dead-end in a web page; bad TLS
   is accepted silently; airplane mode gives a dialog; no dark mode, no accessibility labels,
   portrait-locked; no saved campuses).
6. A handoff summary: the screen-by-screen implementation order for the repo, which existing files
   change (`ui/theme/Theme.kt`, `ui/AppRoot.kt`, `ui/JobsScreen.kt`) and which are new
   (`ui/UploadSheet.kt`, `ui/ReleaseScreen.kt`, `ui/AccountScreen.kt`, `ui/QueueScaffold.kt`), with
   the state each screen consumes from `AppState`.

## Self-review before you show me anything

Every listed screen and state is drawn; both themes exist for all of them; contrast measured, not
assumed; no status is color-only; every tap target measured; all strings are real English with real
school content; no invented data (no charts, avatars, maps, AI); every visible value traceable to the
list of things the app can know; expressive components actually used (pills, morphing FAB, container
selection, spring motion) rather than Material 2021 with a new palette.

## Realistic sample content to use

Host `mobileprint.gmu.edu`, API `4.11.24.1`, user "A. Rahman" (`arahman3`), balance `$4.15` split
across `Mason Money $2.65` and `Semester Credit $1.50`, server spend order Mason Money first. Granted
cost centers: `BUSD-CHEM-UG` (Chemistry undergraduate teaching grant, marked as granted) and
`LIB-PRINT-241` (Library work-study). Jobs:
`CHEM-213-lab-report.pdf` 8 pages B/W duplex ×1, charge to `BUSD-CHEM-UG`, `$0.00 to me` (the grant
pays $0.80), held 22 minutes; `STAT-final-notes.docx` 12 pages, 4 color, own balance, `$1.60`, held
3 hours, expiring in 2 days; `essay-draft-3.pdf` 26 pages B/W simplex released at "Governmental
Center 2nd Flr"; one job with status *problem* and the server's reason; and one selection whose costing
came back `-1`. Printers: `Federation Hall 210 — HP LaserJet E60155`,
`Library 2nd Floor — KM C6100i2`, `Research Hall Commons — HP E77833`, `Enterprise Hall 102 — KM
4070i2`, last-used marked. Transactions: `TF +$20.00 Mason Money transfer`, `PA +$10.00 PayPal`,
`PF -$0.50 gateway fee`, `CR -$1.25 print charge`, plus a `CR $0.00` line for a job the grant paid —
all decoded in plain words.

## Questions I have already answered (do not re-ask)

Android phone first (portrait, 360×800 dp), both light and dark, English only for now, GMU as the
reference deployment with a second "generic campus" variant shown only where capabilities differ,
dynamic color off by default, no tablet deliverable beyond a reflow note, and no changes to the
backend: anything the server cannot answer must not appear in the design.

--- END PROMPT ---

## Follow-up prompts (iteration rounds)

Run these as separate turns after the first generation, not folded into the opening prompt.

1. **Directions.** "Give me three genuinely different Queue layouts — balance-first, queue-first,
   and task-first — each with the same content, and tell me which one you would ship for a student at
   a printer and why."
2. **Capability matrix stress test.** "Redraw the Queue and Account screens for a campus where web
   upload is denied, QR release is off, add-funds is denied, cost centers are not allowed, and the
   server demands CAS. Show the hidden-feature explanations, and make sure nothing is left as a
   dead control."
3. **Accessibility audit.** "Audit the board: label every control's TalkBack string, flag anything
   below 4.5:1, flag any status that relies on color, and show the 200 % font-scale version of the
   Queue and the cost-estimate dialog."
4. **Dark + motion pass.** "Bring the dark theme up to parity screen by screen, then annotate the
   motion for the five moments that change state (cost updated, selection bar arrived, job released,
   upload progressing, error appeared) with `MotionScheme` spec names and durations, plus the
   reduced-motion fallback."
5. **Handoff.** "Export the design bundle and produce an implementation brief for Claude Code against
   `the repo root`: token diffs for `Theme.kt`, new screen files, the `AppState` fields each
   screen consumes, and the Compose component list. Keep the existing phase-driven routing — do not
   introduce Navigation Compose."

## Repo pointers for Claude Design

| Path | Why it matters |
|---|---|
| `app/src/main/java/dev/ahnafnafee/masonprint/ui/theme/Theme.kt` | the live palette, shape scale, `MaterialExpressiveTheme` wiring; the spec must extend this, not restart it |
| `app/src/main/java/dev/ahnafnafee/masonprint/ui/AppRoot.kt` | current Connect / Sign-in screens, failure card, certificate dialog, empty state |
| `app/src/main/java/dev/ahnafnafee/masonprint/ui/JobsScreen.kt` | current Queue: balance hero, job cards, selection bar, funding and printer pickers, cost estimate |
| `app/src/main/java/dev/ahnafnafee/masonprint/core/Session.kt` | `AppState` — the exact data every screen may render, and `CostPreview` including the server's refused-costing case |
| `app/src/main/java/dev/ahnafnafee/masonprint/core/PrintRequests.kt` | what can actually be sent per job (finishing options, per-job cost-center code) — the ceiling on what the finishing sheet may offer |
| `app/src/main/java/dev/ahnafnafee/masonprint/data/model/SettingsDocument.kt` | `Capabilities`: every server-driven switch the UI must hide rather than disable |
| `docs/CLONE-PLAN.md` §5 | the six surfaces and what each replaces |
| `docs/FINDINGS.md` §12, §14 | the payment verdict (no bypass) and the U1–U14 defect list the design has to answer |
| `docs/FUNDING-MODELS.md` | who pays: purses and their spend order, grants vs. cost centers, `-1` vs `0`, and the exact GMU evidence behind each claim |
| `docs/PHAROS-API-FINDINGS.md` | the wire contract, with the verbatim source lines each field comes from |
