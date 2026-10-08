# AI Status

## Current Phase
**Phase 2 core is COMPLETE** (all planned items merged; P2-PR11 deferred per Decision 016). **Launch preparation for NearBird** is done on the engineering side; the remaining launch gates need the owner (below).

- **App:** **NearBird**, `io.github.rohanr1904.nearbird`, distributed via GitHub Releases plus a website (no Play Store).
- **Working copy:** `C:\dev\MeshUp`. A second worktree, `C:\dev\MeshUp-ops`, is used for merges and parallel branches. The OneDrive copy is stale.
- **Merge flow:** a reviewed PR is synced with `main`, must pass all 5 CI checks (test and lint, debug APK, two reproducible release builds, byte comparison), and is then merged with a merge commit.

## Completed (merged to main)
| PR | Item |
|---|---|
| #1–#5 | Phase 1: foundations, Internet opt-in gate (Decision 013), app shell, name step / Profile / Settings, Rooms tab |
| #6 | Phase 2 plan + Decision 015 |
| #7 | P2-PR1 Noise replay-window fix (R-5.1) |
| #8 | P2-PR2 TTL clamp (R-5.3) |
| #9 | P2-PR3 release log stripping + HMAC redaction (R-5.5) |
| #10 | P2-PR4 bounded striped packet processing + per-link/global rate limits (R-5.2) |
| #11 | P2-PR5 signing fails closed; stubs deleted (R-5.4a) |
| #12 | P2-PR6 identity-reset safety, typed `reset` (R-5.4b) |
| #13 | P2-PR7 DB v5 encrypted outbox (R-1) |
| #14 | Launch scope (Decision 016), GPLv3 README statement, privacy policy draft |
| #15 | P2-PR10 platform compliance; no background location on API 31+ (R-9) |
| #16 | NearBird identity (R-10); APK trust = pinned cert or own signer |
| #17 | P2-PR8 durable router, rehydrate, process-level owner (R-1); TD-01 flipped |
| #18 | AI status/handoff refresh; Decision 015 amendment 2 (Failed at ~7.5 min) |
| #19 | P2-PR9 ACK-timeout resend (D3), Failed + Retry (D5), session-race fix, lock-order fix |
| #20 | Open-source licences screen (GPLv3 text bundled, 21 grouped third-party entries) |
| #21 | Version restarts at 1.0.0 (phone versionCode 1, Wear 1_000_000_001) |
| #22 | Device test plan: `docs/release/NEARBIRD_DEVICE_TEST_PLAN.md` |

All three characterization `knownDefect_*` families are now inverted and green: R1 replay, R-5.3 TTL and TD-01 outbox loss.

## In Progress
- Nothing. The release dry run passed: CI's unsigned release is `io.github.rohanr1904.nearbird`, labelled NearBird, min API 26 / target 37, `nearbird-*` artefacts, checksums verify, Arti bundled, debug log strings stripped.
- P2-PR9 review follow-ups (non-blocking):
  - Retry in a plain-peer-ID chat with no stored fingerprint binds to the currently authenticated peer. Proper fix: store the recipient fingerprint in history.
  - Verify the expected fingerprint inside the transport, to close the residual session-swap window.
  - Delete-vs-retry is narrowed, not closed: the history delete doesn't take the router lock.
  - Known product gap: a peer unreachable right after the first send means a silent 1 h expiry; the message keeps "Sent" and shows no Retry.
- Licences screen (#20) needs legal review:
  - Nordic/NanoHTTPD/JSR-305 licences came from project knowledge.
  - Natural Earth and NewHope "public domain" comes from in-repo comments.
  - Arti's ~500 Rust crates are summarised in one entry.

## Launch gates (Decision 016)
1. **Physical devices (2–3 phones):**
   - exactly-once delivery after a process kill
   - multi-hop relay
   - BLE discovery with `neverForLocation` on API 31–37
   - background scanning on API 29–30
   - boot/FGS start on API 34–37
2. **Owner signing key:** DONE. Created 2026-10-08 (alias `nearbird`, RSA 2048); certificate pinned in `gradle.properties` (#24). Signing runs on the owner's machine with `tools/reproducible-builds/sign-release.sh`, which reads `BITCHAT_GITHUB_KEYSTORE`, `..._KEY_ALIAS`, `..._KEYSTORE_PASSWORD` and `..._KEY_PASSWORD` from the environment.
3. **Licence + privacy (Decision 017, legal-compliance playbooks A–D run 2026-10-08; see `docs/legal/`):**
   - GPLv3 open source **confirmed by the owner**; donations only, no perks; donation links stay off until CA/lawyer review.
   - Licence verification: 194 JVM artifacts + 465 Rust crates checked, **no GPL-incompatible licence found** (`docs/legal/THIRD_PARTY_LICENCES.md`). Open: JSR-305 notice, `ring` (Apache-2.0 AND ISC), MPL-2.0 `option-ext`, proprietary ML Kit / Play Services (NEEDS LAWYER; blocks F-Droid). Licences-screen corrections: `docs/legal/LICENSES_SCREEN_DIFF.md`.
   - GPLv3 release checklist (`docs/legal/GPL_RELEASE_CHECKLIST.md`): NOTICE and `TRADEMARKS.md` merged (#28). Branch `claude/hopeful-mayer-9mk0s2` (not yet merged) adds: the in-app copyright/no-warranty/modified lines (B3, proposed wording, lawyer to confirm); NearBird text for the Wear app name, hotspot share screen and download page, manifest label and permission rationale (B8); release manifests renamed `BITCHAT_*` → `NEARBIRD_*`; an attested `nearbird-vX.Y.Z-source.tar.gz` job in `release.yml` (B1); and an updated maintainer guide (it still pointed at the upstream repo and `bitchat-android-*` names). **Still open:** launcher icon (owner must supply), README rewrite, `fastlane/` metadata, README screenshots check.
   - Updater bug fixed on the same branch: `GitHubReleaseClient` could select `nearbird-universal-unsigned.apk` (first "universal" match), which the signer check always rejects, so in-app updates would never install. It now skips `*-unsigned` and prefers `nearbird-universal.apk`.
   - Privacy policy: 6 statements fail against the code (permissions list, media storage, retention limits, hotspot/Wi-Fi Aware, log-redaction wording, location/Play Services). Fixed draft: `docs/legal/proposals/PRIVACY_POLICY.filled.md` (GitHub Pages URL, repo URL, 18+, grievance line; **contact email stays a placeholder until a dedicated mailbox exists**).
   - Remaining: **lawyer/CA review** using `docs/legal/LAWYER_BRIEF.md` (11 questions: GPL §5(a)/§6, ML Kit, public-domain code, trademark, DPDP, FCRA, income tax, GST, FEMA).
4. **Name/domain:** web search finds no "NearBird" messenger; `nearbird.com`/`.org` are registered (since 2007); `nearbird.app` appears available (no purchase until device testing passes, Decision 017). **Owner:** trademark searches (IP India classes 9/38/42, WIPO, USPTO), via the Claude Cowork request; create a dedicated contact mailbox.
5. **Publish v1.0.0 to GitHub Releases:** after gates 1, 3 and 4, with explicit owner approval.

## Deferred until after launch (Decision 016)
- **P2-PR11 Arti native stop fix.** The Settings restart prompt is the mitigation: Tor guard connections persist after Internet OFF until a restart.
- **Review follow-ups:**
  - move the voice relay delay and private-file save off the packet-processor stripes
  - type-specific caps for NOISE_HANDSHAKE and for announces that skip dedup
  - H12: `SecurityManager.kt:84-85` / `WifiAwareMeshService.kt:1303`, where `ttl >= 7` lets an announce bypass dedup
  - remaining full peer IDs in `Log.w`/`Log.e`
  - `Log.println` relay text
  - NoiseSession lock split; the receive side doesn't count messages toward rekey
- **ML Kit replacement** with an offline QR decoder (Decision 013 addendum: possible Google telemetry).
- **Minor:**
  - the legacy header shows `##name`
  - the legacy nickname edit bypasses `DisplayNameValidator`
  - `meshup_*` strings are untranslated
  - receipts and favourite notifications are dropped, not queued, while Internet is OFF

## Blockers / environment
- **TD-29:** five Robolectric SQLite test classes fail on Windows only; all pass on Ubuntu CI. The classes are ConversationDatabaseTest, ConversationRepositoryTest, IncomingMessageAdmissionTest, MediaSendingManagerMigrationTest and NostrDirectMessageHandlerTest.
  - **New evidence (2026-10-08):** a DB test failed with the same `SQLITE_CANTOPEN` until its name was shortened, and `git worktree remove` failed with "Filename too long".
  - **Likely root cause:** the **Windows MAX_PATH limit** on Robolectric's per-test temp paths.
  - **Possible fixes** (both need owner approval because they touch build or system configuration):
    - (a) a short `java.io.tmpdir` / Robolectric temp dir for the test task in `app/build.gradle.kts`
    - (b) Windows long paths (`LongPathsEnabled`, a system setting) plus `git config core.longpaths true`
  - **Until then:** keep new DB test names short, and treat CI as the gate.
- Don't build inside OneDrive (file-lock `AccessDeniedException`).

## Verification status (latest)
- Unit tests (Windows): 835 run on the P2-PR9 branch; failures are the TD-29 set only.
- CI: green on every merged PR, including the reproducible release byte comparison.
- Emulator (Pixel_7, API 36):
  - Internet OFF gives zero app sockets.
  - Internet ON routes relays via Tor SOCKS.
  - The restart prompt clears the Tor connections.
  - Onboarding with location denied completes; the FGS runs as `connectedDevice` only.
  - The name step, Rooms and Profile were exercised.
- Physical devices: **not run** (launch gate).

## Next Action
1. Owner:
   - create the signing key and send its certificate SHA-256 (not the password); engineering pins it in `gradle.properties`
   - confirm GPLv3 / legal
   - run the device test session using `docs/release/NEARBIRD_DEVICE_TEST_PLAN.md`
   - approve publishing v1.0.0 to GitHub Releases (irreversible)
2. Engineering, once the owner's inputs arrive: pin the certificate, sign and publish v1.0.0 (after approval), and fix any device-test failures.
3. Phase 3 (password rooms) before launch only if password rooms are wanted in v1; otherwise after launch, together with the deferred follow-ups.

## Last Updated
2026-10-08
