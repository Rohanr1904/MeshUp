# AI Status

## Current Phase
Phase 0.5 — Baseline: **COMPLETE**, TD-29 confirmed Windows-only via CI. Phase 1 plan **APPROVED** (`docs/IMPLEMENTATION_PLAN.md`). PR-1 Foundations committed (`e5b7ff9`, branch `phase1/pr1-foundations`). PR-2 Internet opt-in gate committed on stacked branch `phase1/pr2-internet-gate` after security review + fixes. Nothing pushed. Next: PR-3 (app shell + adapters).

## Completed
- Phase 0 analysis docs (PROJECT_ANALYSIS, MESH_ARCHITECTURE, SECURITY_REVIEW, TECHNICAL_DEBT, TARGET_ARCHITECTURE rev 2)
- Decisions 011–014 recorded in `docs/product/08_DECISIONS.md`: GPLv3, wire-compatible with BitChat, Internet opt-in default OFF, Wear out of scope but compiling
- Local baseline: `:app:assembleDebug` PASS, `lintDebug` PASS, `:wear:assembleDebug` PASS; unit tests 612 / 23–24 failed / 3 skipped
- OneDrive ruled out as the cause of the test failures (same result in a plain local clone)
- Characterization tests added, 12 tests, all passing (test-only, no production change):
  - `app/src/test/kotlin/com/bitchat/android/noise/NoiseSessionReplayWindowCharacterizationTest.kt` (R1 replay; 4 controls + 2 known-defect)
  - `app/src/test/kotlin/com/bitchat/android/mesh/PacketRelayTtlCharacterizationTest.kt` (R-5.3 TTL; 2 controls + 2 known-defect)
  - `app/src/test/kotlin/com/bitchat/android/services/MessageRouterRestartCharacterizationTest.kt` (TD-01; 1 control + 1 known-defect)

## In Progress
- None. Open items carried forward:
  - PR-3: `MessagingService.sendMessage` targets the legacy "currently selected" context; consider an explicit `peerId` target.
  - PR-4: Settings switch must toggle via `InternetGate.setEnabled(...)` (single process-wide `NetworkSettings`); hide About-sheet update/Tor controls while OFF (review L3).
  - M3 ML Kit telemetry: accepted as a known exception (Decision 013 addendum); follow-up: offline QR decoder.
  - Residuals from PR-2 review: read receipts / favourite notifications are dropped (not queued) while OFF (L2); `ChatViewModel` ignores DROPPED for geohash DMs; brief Tor-start window if the gate flips OFF during an ON reconcile (relays stay blocked).

## Blockers
- **TD-29 (not blocking):** 5 Robolectric SQLite test classes fail on Windows only. CONFIRMED Windows-only: GitHub Actions run 37591481044 on the same commit `8d7de5e` (ubuntu-24.04) passed `testDebugUnitTest`, `lintDebug`, `assembleDebug` and the reproducible release builds. Locally, compare failing-test sets against this baseline.
- **Dev environment:** builds inside the OneDrive folder hit `AccessDeniedException` locks on `app/build/intermediates`. Develop from a non-synced path (e.g. `C:\Users\sande\meshup-baseline`, or move the working copy).
- README/PRIVACY_POLICY "public domain" statements still need correcting (Decision 011 follow-up; licence-text change needs its own reviewed PR).

## Verification Status
- Static analysis: done, key claims re-verified
- Build: app debug PASS, wear debug PASS, lint PASS (Windows, JBR 25)
- Unit tests: full suite 612 run, 23–24 failed (TD-29), 3 skipped; new characterization tests 12/12 pass
- PR-1 (2026-10-07, Windows): meshup tests 18/18 pass; full suite 642 run / 23 failed / 3 skipped; failing classes = TD-29 set only (ConversationDatabaseTest, ConversationRepositoryTest, IncomingMessageAdmissionTest, MediaSendingManagerMigrationTest, NostrDirectMessageHandlerTest). `:wear:assembleDebug` PASS; `lintDebug` completes (abortOnError=false), only 2 `UseKtx` warnings in new code, matching legacy style.
- Emulator (Pixel_7 API 36, PR-2 build): fresh install, switch OFF, mesh service running -> **zero app-uid TCP/UDP sockets** in /proc/net after 60 s (twice, before and after review fixes). Switch preset ON (tor_mode ON) -> relay WebSockets go to 127.0.0.1:9060 (Tor SOCKS); 3 external TCP sockets (ports 8080/443) INFERRED to be Tor ORPorts (not verified). Runtime toggle not exercised (no UI until PR-4).
- PR-2: unit suite 661 run / 23 failed (TD-29 set only) / 3 skipped; app+wear assemble PASS; lint PASS.
- Physical devices: NOT run

## Next Action
1. Working copy is now `C:\dev\MeshUp` (the OneDrive copy is stale from PR-1 onward).
2. PR-1 and PR-2 pushed as stacked PRs; next PR-3 (app shell + adapters).


## Last Updated
2026-10-07
