# AI Status

## Current Phase
**Phase 2: Reliability + engine hardening.** The core work is merged to `main`; P2-PR9 is in review. Launch preparation for **NearBird** has started (Decision 016).

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

All three characterization `knownDefect_*` families are now inverted and green: R1 replay, R-5.3 TTL and TD-01 outbox loss.

## In Progress
- **P2-PR9** (branch `phase2/pr9-resend-retry`): ACK-timeout resend (D3), Failed + Retry (D5), and the session-race fix. Security and mesh reviews are running.
  - Timing: with no ACK, the message becomes `Failed("No delivery confirmation")` at **about 7.5 min**. That is four resends at +30 s / +1 m / +2 m / +2 m, then one final 2 m wait for an ACK. A late ACK still upgrades the status to Delivered.

## Launch gates (Decision 016)
1. **Physical devices (2–3 phones):**
   - exactly-once delivery after a process kill
   - multi-hop relay
   - BLE discovery with `neverForLocation` on API 31–37
   - background scanning on API 29–30
   - boot/FGS start on API 34–37
2. **Owner signing key:** create the keystore, set `BITCHAT_GITHUB_RELEASE_CERT_SHA256` in `gradle.properties`, then sign with `tools/reproducible-builds/sign-release.sh`. The script reads `BITCHAT_GITHUB_KEYSTORE`, `..._KEY_ALIAS`, `..._KEYSTORE_PASSWORD` and `..._KEY_PASSWORD` from the environment.
3. **GPLv3:** the owner confirms it fits the business model (legal advice if unsure) and publishes the source. Finalise the `PRIVACY_POLICY.md` placeholders (contact, URLs, minimum age) and publish it on the website.
4. **Trademark/domain check** for "NearBird".

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
1. Address the P2-PR9 review findings, then PR, CI and merge.
2. Owner:
   - create the signing key
   - confirm GPLv3 / legal
   - schedule a device test session
3. Then, depending on whether password rooms are wanted in v1:
   - if yes: Phase 3 planning (rooms)
   - if no: a release-pipeline dry run and a licences screen

## Last Updated
2026-10-08
