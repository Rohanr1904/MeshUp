# AI Handoff

## From
Chief engineer (Phase 0 — Repository Intelligence), 2026-10-07; session 2 re-verified Phase 0 and established a local build baseline

## To
Claude Code (Phase 0.5 baseline verification → Phase 1 planning)

## Objective
Map the inherited BitChat Android codebase accurately so implementation can start without re-investigating it.

## What was inspected
Git history/remotes, Gradle/version catalog/locks, manifest, CI workflows, all `app/src/main` packages (mesh, wifi-aware, protocol, noise, crypto, identity, sync, services, service, ui, onboarding, nostr, geohash, hotspot), test inventory, upstream specs in `docs/`, `docs/product/*`, LICENSE/README/PRIVACY_POLICY. Static reading only.

## Key findings
1. **Licence:** `LICENSE.md` = GPLv3 (upstream switched from MIT on 2026-02-28, `fb2bb64`); `README.md:30` and `PRIVACY_POLICY.md:156` wrongly say public domain.
2. **No production code changed yet;** full upstream history preserved (merge `8d7de5e`, upstream parent `6803d6d`).
3. **Reliability gap:** DM outbox is RAM-only (`services/MessageRouter.kt:84`); `Sending` rows are orphaned after process death; no ACK-timeout resend; relay-side store-and-forward is dead code (`mesh/StoreForwardManager.kt`).
4. **Persistence:** only DMs are in SQLite (`services/ConversationRepository.kt`, AES-GCM payloads, plaintext metadata). Public/channel messages, outbox and all mesh state are RAM-only. No Room.
5. **Routing:** TTL-7 flooding; relay unconditional while TTL ≥ 4; optional v2 source routing. TTL is unsigned and not clamped on ingress.
6. **Transports:** BLE dual-role GATT (on); Wi-Fi Aware (off by default, debug-gated, hard-coded PSK); BLE duplicates `MeshCore` instead of implementing `MeshTransport`.
7. **Security:** Noise XX core is solid; the replay-window bug is still open (`noise/NoiseSession.kt`); unbounded per-sender actors; stable peer ID in BLE scan response; sender fails open on signing; ~1.2k logs with no release stripping.
8. **Internet on by default:** Tor + Nostr relay connect at every process start (`BitchatApplication`, `nostr/NostrBackgroundRuntime.kt`).
9. **UX gap:** one `ChatScreen` + sheets, no navigation (navigation-compose unused), no display-name onboarding, rooms are `/join` commands, and the password check is a stub (`ui/ChannelManager.kt:125`).
10. **Build/release maturity is high:** STRICT dependency locks, reproducible double builds, provenance. But: 0 instrumented/Compose tests, and the release identity is upstream's (`com.bitchat.droid`, cert pin, URLs).

## Important file paths
`app/src/main/java/com/bitchat/android/`: `BitchatApplication.kt`, `MainActivity.kt`, `ui/ChatViewModel.kt`, `ui/ChatScreen.kt`, `services/MessageRouter.kt`, `services/ConversationRepository.kt`, `services/AppStateStore.kt`, `mesh/{MeshService,UnifiedMeshService,BluetoothMeshService,MeshCore,PacketProcessor,SecurityManager,PacketRelayManager,FragmentManager,PowerManager}.kt`, `noise/NoiseSession.kt`, `crypto/EncryptionService.kt`, `service/MeshForegroundService.kt`, `ui/ChannelManager.kt`; `wear/build.gradle.kts` (shared source list); `gradle/libs.versions.toml`; `AGENTS.md` (repo privacy rules: repo-relative paths only, synthetic data only).

## Architecture conclusions
- Seam to build on: `MeshService` (outbound) / `MeshDelegate` (inbound). Add Domain + Data layers above the engine; do not rewrite the engine.
- Extend `ConversationRepository` (not Room) for outbox/rooms; extend `MessageRouter` (not replace).
- Separate the UI/domain models from the wire `BitchatMessage`.
- See `docs/TARGET_ARCHITECTURE.md` R-1…R-10.

## Security concerns (top)
R1 replay window · R2 unbounded actors · R3 unsigned relayed types + TTL · R4 stable advertised ID · R5/R6 Nostr location notes and auto-receipts · R9 log leakage. Full register: `docs/SECURITY_REVIEW.md` §18.

## Unresolved questions
GPLv3 vs business model · wire compatibility with BitChat (A1) · Internet opt-in (A3) · Wear scope (A4) · `startServices()` idempotency · notifications with no ViewModel alive · FGS boot start on API 34–37 · local git remote/uncommitted state.

## Do Not Touch (yet)
- `protocol/`, `noise/` (incl. `southernstorm`), `crypto/`, `identity/`, packet formats, BLE UUIDs, `bitchat://verify` + `bitchat-verify-*` labels, TTL/relay policy, advertised identifiers. Changes need a decision record + security review.
- `LICENSE.md` and licence statements in README/PRIVACY_POLICY (legal review first).
- Kotlin package names / file locations under `com/bitchat/android/**` (Wear include list, R8 keep rules, Gson persistence).
- SharedPreferences names, Keystore aliases, notification channel IDs, DB name/schema (no migration without a plan).
- `gradle/*.lockfile`, `verification-metadata.xml`, CI workflows, reproducible-build tooling (unless the task is dependency work).
- `BluetoothMeshService`/`MeshCore` unification (deferred until characterization tests exist).

## Verification
- Session 1: static analysis only. The Noise replay bug was confirmed by code tracing and a Python transcription.
- Session 2 (2026-10-07): the chief engineer re-checked the key claims in code (see `TARGET_ARCHITECTURE.md` §6), including an independent hand-trace of the replay shift (`NoiseSession.kt:80-92`: after a 1-step advance, the old bit lands at offset 15, so `highest-1` replays are accepted) and TTL (`PacketRelayManager.kt:58-70,143-145`: no ingress clamp; `ttl ≥ 4` always relays).
- Local build (Windows 11, repo under OneDrive, Android Studio JBR 25 as the Gradle JVM): `:app:assembleDebug` PASS; `:app:testDebugUnitTest` 612 run / 23 failed / 3 skipped. The failures cluster in SQLite-backed Robolectric tests (`SQLITE_CANTOPEN`) and are INFERRED to be environment-specific (CI is Ubuntu). Not yet confirmed.
- Not run: `lintDebug`, `:wear:assembleDebug`, emulator, physical devices.

## Phase 0.5 results (2026-10-07)
- Decisions 011–014 recorded (GPLv3; wire-compatible; Internet opt-in default OFF; Wear out of scope but compiling).
- Clean-clone rerun: OneDrive is NOT the cause of the test failures. `lintDebug` PASS, `:wear:assembleDebug` PASS.
- TD-29: all failing classes are Robolectric + conversation SQLite. The root error is `SQLITE_CANTOPEN` in Robolectric's native runtime. CONFIRMED Windows-only (CI run 37591481044 green on `8d7de5e`).
- Characterization tests (12/12 pass) pin R1 replay, TTL non-clamping and TD-01 outbox loss. Each `knownDefect_*` test must be inverted by its fix PR.
- Gotcha: Gradle in the OneDrive folder hit `AccessDeniedException` on `app/build/intermediates`. Build from a non-synced clone (`C:\Users\sande\meshup-baseline` exists: a scratch clone of `8d7de5e` plus copies of the 3 new tests).

## Recommended Next Step
1. Move the working copy out of OneDrive (build locks).
2. **Phase 1 plan written: `docs/IMPLEMENTATION_PLAN.md` (APPROVED 2026-10-07).** Summary: R-3 app shell (NavHost) + display-name onboarding over the `MeshService` seam; R-2 domain models for new screens only; R-6 `NetworkSettings` with the Internet switch gating `BitchatApplication` init (Decision 013; no existing-install migration needed: new applicationId, see plan P1-7). Owner: android-engineer (Sonnet) + ux-product for flows; the chief engineer reviews.
3. Phase 2 opens with R-1 durable outbox (migration v4→v5 plan first) and the R-5 fixes, which flip the `knownDefect_*` tests.

## Commit
Docs only, written to the local workspace; not committed or pushed. Session 2 also edited `TARGET_ARCHITECTURE.md` (rev 2), `PROJECT_ANALYSIS.md` §5 (baseline), `AI_STATUS.md` and this file. Build outputs exist under `app/build/` (gitignored). Phase 0.5 added `docs/product/08_DECISIONS.md` (011–014), TD-29 + A.1 in `TECHNICAL_DEBT.md`, the R1 evidence upgrade in `SECURITY_REVIEW.md`, and the 3 test files above. Nothing committed. Files: `docs/PROJECT_ANALYSIS.md`, `docs/MESH_ARCHITECTURE.md`, `docs/SECURITY_REVIEW.md`, `docs/TECHNICAL_DEBT.md`, `docs/TARGET_ARCHITECTURE.md`, `docs/AI_STATUS.md`, `docs/AI_HANDOFF.md`.
