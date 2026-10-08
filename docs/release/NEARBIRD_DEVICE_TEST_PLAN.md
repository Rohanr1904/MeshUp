# NearBird — Device Test Plan (launch gate)

Decision 016 makes this a **launch gate**: NearBird is not published until every **MUST** item below passes on real phones. Emulators can't do Bluetooth mesh, so these checks need physical devices (Decision 009).

For deeper transport coverage, see the upstream matrix in `docs/device-transport-test-matrix.md`. This plan is the NearBird-specific minimum.

## What you need
- **2 Android phones minimum; 3 are better** (needed for the multi-hop test).
  - Ideally from different manufacturers.
  - Ideally one on Android 12 or newer and one on Android 10–11.
- The **same NearBird build** installed on every phone, from the same commit.
  - Use the CI artefact `nearbird-universal` from the `Debug-apk` artefact of a green run on `main`. Or use a signed release once your key exists.
  - Install with `adb install -r <apk>`. Or copy the APK to the phone and open it; this needs "install unknown apps".
- Each phone **starts clean**: uninstall any previous NearBird/bitchat build first.
- A notebook or the record sheet at the bottom.

**Privacy:** don't record real names, phone numbers, serial numbers, Bluetooth addresses, peer IDs or message contents. Use test phrases like "test 1".

## A. Install and onboarding (each phone)
| # | Step | Expected | Must? |
|---|---|---|---|
| A1 | Open NearBird for the first time | Permission screen. On Android 12+, location says **optional**. | MUST |
| A2 | Grant Nearby devices + Notifications. **Deny location** on Android 12+. | Onboarding continues; there's **no** background-location step on Android 12+. | MUST |
| A3 | On Android 10–11: grant location and the background-location step | It's offered and can be skipped | MUST (on 10–11 phone) |
| A4 | Name step: clear the name and try to continue | "Enter a name" error | MUST |
| A5 | Type a name and continue | The app opens on **Chats** with the bottom bar Chats · People · Rooms · Profile | MUST |
| A6 | Profile tab | Your name, a short fingerprint, the Internet switch is **off**, and Open-source licences | MUST |

## B. Discovery and chat over Bluetooth (2 phones, side by side, Internet OFF)
| # | Step | Expected | Must? |
|---|---|---|---|
| B1 | Wait up to 30 s | Each phone appears in the other's **People** tab | MUST |
| B2 | Repeat B1 with **location denied** on the Android 12+ phone | Still discovered (checks the `neverForLocation` change) | **MUST** |
| B3 | Send a public message in Chats | It appears on the other phone | MUST |
| B4 | People → tap the other person → send a private message | It arrives; the sender shows **Sent**, then **Delivered** | MUST |
| B5 | Turn Bluetooth off on one phone, then back on | Both rediscover each other within about 30 s | MUST |

## C. Messages survive and resend (the core Phase 2 promise)
| # | Step | Expected | Must? |
|---|---|---|---|
| C1 | Move phone 2 out of range (another room, or Bluetooth off). On phone 1, send a private message. | It shows "Sending" (queued) | MUST |
| C2 | Bring phone 2 back in range | The message arrives **once**; phone 1 shows Delivered | MUST |
| C3 | Repeat C1, then **force-stop NearBird on phone 1** (Settings → Apps → Force stop) and reopen it. Then bring phone 2 back. | The message is delivered **exactly once**, with no duplicate | **MUST** |
| C4 | Keep phone 2 away for about 8 minutes after the message was handed to the mesh but not acknowledged. To force this, send while connected and immediately switch phone 2's Bluetooth off. | It shows **Failed** with **Retry** after about 7.5 min | SHOULD |
| C5 | Tap **Retry** with phone 2 back in range | Delivered, with no duplicate on phone 2 | MUST |
| C6 | Queue a message (C1) and wait more than 1 hour | It becomes **Failed** ("Not delivered") | SHOULD |
| C7 | Send while connected, then **immediately** take phone 2 out of range (Bluetooth off) and keep it away for more than 1 hour | It shows **Failed** with **Retry**, not "Sent" forever (Decision 015 amendment 3). Retry with phone 2 back in range delivers it once. | SHOULD |

## D. Multi-hop relay (3 phones)
| # | Step | Expected | Must? |
|---|---|---|---|
| D1 | Place phones in a line, A — B — C, so A and C can't see each other directly (separate rooms) | A sees B, B sees C | MUST |
| D2 | A sends a private message to C | C receives it through B; B can't read it | **MUST** |
| D3 | A sends a public message | C receives it | MUST |

## E. Background and restart
| # | Step | Expected | Must? |
|---|---|---|---|
| E1 | Put NearBird in the background and lock the screen for 5 minutes, then send to it from another phone | A notification arrives | MUST |
| E2 | Reboot the phone and don't open the app | The mesh service starts after boot (persistent notification) and receives messages | SHOULD |
| E3 | Battery saver on, app backgrounded for 10 min | Still discoverable; note any delays | SHOULD |

## F. Privacy and safety
| # | Step | Expected | Must? |
|---|---|---|---|
| F1 | Leave the Internet switch off and use the app | No Internet use. If you can check: the phone's data-usage screen shows ~0 for NearBird. | MUST |
| F2 | Turn the Internet switch on, then off | The restart hint appears; tapping **Restart NearBird** relaunches the app | SHOULD |
| F3 | Profile → Reset identity… → type `reset` | Everything is wiped; there's a new fingerprint and the name step appears again | MUST |
| F4 | Emergency wipe: triple-tap the app title | Instant wipe, no confirmation | MUST |
| F5 | After F3/F4, earlier queued messages are **not** sent | Nothing from before the wipe goes out | MUST |
| F6 | Block a person, then queue a message to them | It's not sent; it shows Failed ("Recipient blocked") | SHOULD |

## G. Compatibility (optional, recommended)
| # | Step | Expected | Must? |
|---|---|---|---|
| G1 | One phone with the original **bitchat** app (Android or iOS), one with NearBird | They can chat publicly and privately (Decision 012: wire-compatible) | SHOULD |

## Record sheet
Copy this table once per test session.

| Field | Value |
|---|---|
| Date | |
| Build (commit / version) | |
| Phone 1 (model, Android version) | |
| Phone 2 (model, Android version) | |
| Phone 3 (model, Android version) | |

| Test | Result (Pass / Fail / Skip) | Notes |
|---|---|---|
| A1–A6 | | |
| B1–B5 | | |
| C1–C7 | | |
| D1–D3 | | |
| E1–E3 | | |
| F1–F6 | | |
| G1 | | |

**For any failure,** note the time and the steps. If possible, capture logs with `adb logcat -d > nearbird-log.txt` right after the failure. Release builds only keep warnings and errors, with IDs redacted.

## After the session
Send the filled record sheet and any logs to the engineering session.
- **Failures** become bug-fix PRs before launch.
- **When all MUST items pass**, the device gate in `docs/AI_STATUS.md` is marked done.
