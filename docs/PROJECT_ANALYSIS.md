# MeshUp — Project Analysis (Phase 0: Repository Intelligence)

> Last updated: 2026-10-07 · Baseline commit `8d7de5e` on `main` · Author: chief engineer (synthesis) with mesh-architect, security-engineer and android-engineer specialists.
> Companion docs: `docs/MESH_ARCHITECTURE.md` (transport/protocol detail), `docs/SECURITY_REVIEW.md` (risk register R1–R27), `docs/TECHNICAL_DEBT.md`, `docs/TARGET_ARCHITECTURE.md`.

**Labels:** CONFIRMED = seen in code, config or git (path cited) · INFERRED = reasoned from cited evidence, not executed · UNKNOWN = not determinable from the repo · RECOMMENDED = proposal.

**Verification level:** static reading of source, config, docs and git history only. **No Gradle build, no unit-test run, no emulator and no physical device** were used (the analysis environment has no Android SDK). Every behavioural claim below is code-level, not runtime-verified.

---

## 1. Repository state

| Item | Finding | Label |
|---|---|---|
| Remote | `origin` = `github.com/Rohanr1904/MeshUp`, single branch `main`. A separate `upstream` remote for `permissionlesstech/bitchat-android` is not visible from the GitHub clone; local remote config is UNKNOWN (the local shell could not be reached during Phase 0). | CONFIRMED / UNKNOWN |
| History | 877 commits. MeshUp commits: `ce1f48b` Initial commit → `1e756e2` "initialize MeshUp AI workspace" → `8d7de5e` "import BitChat Android foundation", a merge whose second parent is upstream `6803d6d` (upstream PR #968, Wear 0.1.4, 2026-10-06). **Full upstream history is preserved**, so `git log`/`blame` on upstream files works. | CONFIRMED |
| Divergence from upstream | MeshUp-specific content is limited to `CLAUDE.md`, `.claude/`, `docs/product/`, `docs/AI_*.md`. **No production code has been modified yet.** | CONFIRMED |
| Size | `app/src/main`: 277 Kotlin files, ~78.6k lines. `app/src/test`: 95 files, 612 `@Test`. `wear/`: 45 Kotlin files (~6.4k lines of its own). | CONFIRMED |
| Upstream version | `versionName 2.0.2`, `versionCode 39`, `applicationId com.bitchat.droid` (`app/build.gradle.kts`). | CONFIRMED |

### 1.1 License — a release-blocking finding
- `LICENSE.md` is **GNU GPL v3** (CONFIRMED). Upstream switched from MIT to GPLv3 on 2026-02-28 (`fb2bb64`, "chore: change license from MIT to GPLv3 (#674)") (CONFIRMED in git).
- `README.md:30` still says "released into the public domain" and `PRIVACY_POLICY.md:156` says "public domain under The Unlicense". Both are **stale and contradict `LICENSE.md`**. Upstream GitHub shows the same inconsistency (GitHub metadata: GPL-3.0) (CONFIRMED, see Sources).
- Per project rules, `LICENSE.md` is authoritative. **Consequence (INFERRED, not legal advice):** any distributed MeshUp APK/AAB must be offered under GPLv3 with corresponding source. A proprietary closed-source distribution of this codebase is not compatible with GPLv3. `docs/product/09_LICENSE_NOTES.md` already flags this as a release gate, and this finding makes it concrete. Bundled third-party code (vendored Noise-Java under `noise/southernstorm`, Arti `libarti_android.so`, NanoHTTPD, BouncyCastle, Tink, ML Kit, Play Services Location) needs a dependency-license inventory before release.
- RECOMMENDED: get legal review of the intended business model *before* Phase 1 investment. Do not edit `README.md`/`LICENSE.md` licensing text without that review.

---

## 2. Repository structure

```
app/                     phone client (Kotlin, Compose) — source of truth for shared mesh/protocol code
  src/main/java/com/bitchat/android/
    mesh/        32 files 10.9k lines  BLE mesh, relay, fragmentation, security checks, power
    wifi-aware/   7 files  2.6k        Wi-Fi Aware transport (package com.bitchat.android.wifiaware)
    protocol/     4 files  1.0k        BinaryProtocol, compression, padding
    noise/        5 files  2.1k        Noise sessions (+ vendored southernstorm Noise-Java)
    crypto/ identity/                  EncryptionService, SecureIdentityStateManager
    sync/         4 files  0.6k        gossip sync (GCS filters)
    services/    17 files  5.3k        MessageRouter, ConversationRepository (SQLite), AppStateStore, meshgraph
    service/      8 files  1.3k        MeshForegroundService, BootCompletedReceiver, TransportBridgeService
    ui/          82 files 29.4k        Compose UI, ChatViewModel, sheets, NotificationManager, debug
    nostr/       30 files  7.6k        Internet transport (Nostr relays), geohash channels, location notes
    geohash/     13 files  2.0k        location channels, live-location privacy gate
    net/                               Tor (Arti) manager, OkHttp provider
    hotspot/      7 files  2.4k        offline APK sharing via Wi-Fi Direct + NanoHTTPD
    onboarding/  14 files  3.1k        permission/Bluetooth/location/battery flows
    features/ favorites/ model/ util/ core/
  src/debug/…/testhook/  debug-only exported test hook receiver
  src/test/              JVM + Robolectric tests (no src/androidTest)
  src/main/jniLibs/      prebuilt libarti_android.so per ABI
wear/                    Wear OS client; compiles selected app sources in place (syncSharedAppSources)
tools/                   arti-build, reproducible-builds, release_gate (ADB device lab), coverage
docs/                    upstream specs + MeshUp product docs (docs/product/) + AI coordination docs
.agents/skills/          upstream agent skills (mesh-lab, ui-visual-review, readme-screenshot-studio)
.claude/                 MeshUp agent definitions and settings
.github/workflows/       android-build.yml, release.yml, fetch-georelays.yml
fastlane/metadata/       store listing text (upstream branding)
```
(Line counts CONFIRMED by `wc -l`.)

### 2.1 Modules and responsibilities
| Module | Responsibility | Label |
|---|---|---|
| `:app` | Phone application; owns all shared mesh/protocol/crypto code | CONFIRMED (`settings.gradle.kts`, `AGENTS.md`) |
| `:wear` | Wear OS app, **same `applicationId com.bitchat.droid`**, minSdk 33; compiles `app` sources via a literal include list (`wear/build.gradle.kts` `sharedSourceIncludes`, `com/bitchat/android/**` paths). Moving or renaming app files can break Wear silently. | CONFIRMED |

### 2.2 Important classes
| Class | Role |
|---|---|
| `BitchatApplication` | Process init: PowerManager, Tor, Nostr runtime, persistence restore, FGS start |
| `MainActivity` (877 l) | Onboarding orchestration, mesh service wiring, deep links |
| `ui/ChatViewModel` (1,733 l) | Main VM; implements `BluetoothMeshDelegate`; constructs 9 managers |
| `services/AppStateStore` (1,120 l) | Process-wide in-memory state (peers, messages, unread) that survives Activity recreation |
| `services/ConversationRepository` (1,945 l) | SQLite persistence for private conversations (AES-GCM payloads) |
| `services/MessageRouter` (428 l) | Private-message routing: MESH / NOSTR / QUEUED (in-memory outbox) |
| `mesh/MeshService` / `UnifiedMeshService` | Transport-agnostic facade consumed by UI; picks BLE vs Wi-Fi Aware |
| `mesh/BluetoothMeshService` (1,660 l) | BLE coordinator (duplicates `MeshCore` logic) |
| `mesh/MeshCore` (1,092 l) | Coordinator used by Wi-Fi Aware only |
| `mesh/PacketProcessor`, `SecurityManager`, `PacketRelayManager`, `FragmentManager`, `PeerManager`, `PowerManager` | Receive pipeline, validation/dedup, relay policy, fragmentation, peers, duty-cycling |
| `noise/NoiseSession(Manager)`, `crypto/EncryptionService`, `identity/SecureIdentityStateManager` | Noise XX sessions, Ed25519 signing, identity storage |
| `service/MeshForegroundService`, `MeshServiceHolder`, `TransportBridgeService` | FGS lifecycle, process singletons, BLE↔Wi-Fi bridge |

---

## 3. Lifecycles

### 3.1 Application startup (CONFIRMED)
1. `BitchatApplication.onCreate` runs these in order, each in `try { } catch (_: Exception) { }` so failures are **silent**: `PowerManager.start` → `ArtiTorManager.init` (Tor **ON by default**) → `RelayDirectory.initialize` → favorites/location-notes init → `AppStateStore.initializeConversationPersistence` (async SQLite restore) → Nostr identity warm-up → theme/UI/debug prefs → `WifiAwareController.initialize(enabled=false by default)` → geohash registries → **`NostrBackgroundRuntime.initialize` (connects to Nostr relays and subscribes to account DMs)** → `MeshForegroundService.start`.
2. `MainActivity.onCreate` creates `PermissionManager`, gets the `MeshServiceHolder` singletons and builds `ChatViewModel` with a hand-written factory (no DI framework). It then renders `OnboardingFlowScreen`.
3. Onboarding state machine (`onboarding/OnboardingState.kt`): `CHECKING → BLUETOOTH_CHECK → LOCATION_CHECK → (battery check deferred on first run) → PERMISSION_EXPLANATION → PERMISSION_REQUESTING → [BACKGROUND_LOCATION_EXPLANATION, skippable] → INITIALIZING → COMPLETE`. The steps are imperative methods on MainActivity.
4. `initializeApp()`: `delay(1000)`, re-check permissions, `unifiedMeshService.delegate = chatViewModel`, `startServices()`, start FGS, handle notification/deep-link intents, `delay(500)`, then `COMPLETE` → `ChatScreen`.
- The FGS start is attempted in three places (Application, `MainActivity.onCreate`, `initializeApp`). Idempotency of `startServices()` is UNKNOWN.

### 3.2 Message lifecycle (CONFIRMED unless noted)
**Outbound DM:** `InputComponents` → `ChatViewModel.sendMessage` → (`/command` → `CommandProcessor`) → `PrivateChatManager.sendPrivateMessageDurably`. That step writes the row to SQLite with status `Sending` first and aborts if the write fails. It then calls `MessageRouter.sendPrivate`, which returns one of:
- `MESH`: peer connected and Noise session ready → `mesh.sendPrivateMessage` (Noise-encrypted, flooded with TTL 7 unless a source route applies)
- `NOSTR`: mutual favourite with a known npub → Internet (Tor) fallback
- `QUEUED`: in-memory outbox (≤100 per conversation, 24 h, 2 s tick) plus a Noise handshake with 5/15/30/60 s backoff
**Inbound:** transport → `PacketProcessor` (per-sender actor) → `SecurityManager.validatePacket` (signature, dedup) → `MessageHandler` (Noise decrypt, auto-send `DELIVERED` ack) → `MeshDelegate.didReceiveMessage` → `MeshDelegateHandler` → `AppStateStore` / `PrivateChatManager` → `NotificationManager`.
**Status model:** `DeliveryStatus` = `Sending | Sent | Delivered(to,at) | Read(by,at) | Failed(reason) | PartiallyDelivered(n,m)`. A MESH-routed DM stays `Sending` until an ACK arrives. **Nothing resends a message if the ACK never arrives**, and a message lost with the process stays `Sending` in the DB forever (CONFIRMED by absence of any re-queue code).
**Public/channel messages:** broadcast with an Ed25519 signature but no encryption. They have no delivery status and live in RAM only, apart from neighbour gossip resync.

### 3.3 Peer discovery lifecycle (CONFIRMED)
BLE dual-role scanning and advertising (duty-cycled by `PowerManager`) → GATT connect (≤8 links) → signed ANNOUNCE (nickname, Noise and Ed25519 public keys, neighbour TLV) → `PeerManager` (stale after 180 s) → `didUpdatePeerList` → `AppStateStore.setPeers` → `MeshPeerListSheet`. Identity unification across mesh peer ID, Noise key, Nostr alias and favourite ID is spread over `ContactDirectory`, `ContactIdentityResolver`, `ConversationAliasResolver`, `AppStateStore` and `PrivateChatManager`.

### 3.4 Connection lifecycle (CONFIRMED; detail in MESH_ARCHITECTURE §3–4)
Scan result gated by RSSI threshold per power mode, existing connection and attempt throttle → connect → request MTU 517 (the negotiated value is ignored; 512-byte frames are assumed) → service discovery → notifications → per-link FIFO write queue. Connect retries: 3 × 5 s. Wi-Fi Aware (off by default, Android 10+): publish/subscribe → NDP with hard-coded PSK → TCP with 4-byte length framing.

### 3.5 Persistence lifecycle (CONFIRMED)
| Data | Store | Encrypted | Survives process death |
|---|---|---|---|
| Private conversations + delivery status | SQLite `private_conversations.db` v4 (`ConversationRepository`); 1,000 msgs/conversation, 20,000 total | Payload AES-256-GCM (Keystore key, AAD); **metadata columns plaintext** | Yes |
| Public, `#channel` and geohash messages | `AppStateStore` / `ChatState` RAM | — | **No** |
| DM outbox | `MessageRouter.outbox` RAM | — | **No** |
| Channel passwords/keys | `ChannelManager` RAM | — | No |
| Identity keys, favourites, verified fingerprints, seen-ID sets | EncryptedSharedPreferences (`security-crypto` 1.1.0, deprecated) | Yes | Yes |
| Nickname, joined channels, blocked users, last geohash | plain SharedPreferences (`bitchat_prefs`, `geohash_prefs` + ~10 more files) | No | Yes |
| Received media/files/voice | `filesDir/files/incoming` | **No** | Yes |
| Relay/dedup/fragment/gossip state | RAM | — | No |
There is no Room. `MessageRetentionService` is fully commented out. Panic wipe (triple-tap the title) crypto-erases the DB, rotates identity and clears media. The code path is CONFIRMED but not device-verified.

---

## 4. Features inherited (user-visible)
| Feature | Offline? |
|---|---|
| Public mesh chat (multi-hop), peer list with RSSI, "people nearby" notification | Yes |
| Private DMs over Noise XX with delivery and read receipts | Yes |
| `#channels` via `/join` slash commands, optional password (**verification stub returns `true`**) | Yes |
| Favourites, mutual-favourite Nostr DM fallback | Fallback needs Internet |
| QR contact verification (`bitchat://verify`, challenge/response over Noise) | Yes |
| Images, files, voice notes, private media, live push-to-talk voice | Yes |
| Geohash location channels and location notes (Nostr, Tor on by default) | **Needs Internet** |
| Hotspot APK sharing (Wi-Fi Direct + NanoHTTPD + QR) | Sharing offline; fetching the APK from upstream GitHub needs Internet |
| Panic wipe, Wear OS companion, 34 locales (incl. hi, bn, ne, pa) | Yes |
| Debug settings sheet (transport/relay kill-switches, mesh graph), reachable from About | Yes |

---

## 5. Build / test / release workflow (CONFIRMED)
- **Toolchain:** AGP 9.3.1, Kotlin 2.4.10, compile/target SDK 37, minSdk 26, JDK 21, Compose BOM 2026.06.01. **STRICT dependency locking** on every configuration plus `gradle/verification-metadata.xml`, so any dependency change needs `--write-locks` and a metadata update (`docs/reproducible-builds.md`).
- **Local build prerequisites:** JDK 21, Android SDK platform 37 + build-tools 37.0.0, network for the first resolve, Robolectric runtime download. Commands are in `AGENTS.md` (`./gradlew :app:assembleDebug :wear:assembleDebug`, `testDebugUnitTest lintDebug`).
- **Lint:** baseline with 54 issues (`app/lint-baseline.xml`); `abortOnError=false`, `checkReleaseBuilds=false`.
- **Tests:** 612 JVM tests (33 Robolectric files). Strongest coverage: protocol (58), mesh (108), UI logic (167). **0 instrumented tests, 0 Compose UI tests**, crypto 1 test, identity 1. No tests for `GossipSyncManager`, `StoreForwardManager`, `MeshCore`, `UnifiedMeshService`, GATT managers, `WifiAwareMeshService` core, FGS/boot, or the Noise replay window. `docs/test-implementation-plan.md` milestones 1–10 are not started. The documented `checkChangedLineCoverage` Gradle task does not exist.
- **CI** (`.github/workflows/android-build.yml`, actions SHA-pinned): Arti checksum verify → `testDebugUnitTest` → `lintDebug` → `assembleDebug` → reproducible release built twice and byte-compared. `release.yml`: tag-triggered double build + provenance attestation, unsigned output; signing is local (`tools/reproducible-builds/sign-*.sh`). There is no emulator/device CI job.
- **Local baseline (2026-10-07, Windows 11, repo under OneDrive, Gradle launched with Android Studio JBR 25):** `:app:assembleDebug` **BUILD SUCCESSFUL** (3m11s). `:app:testDebugUnitTest` **612 tests, 23 failed, 3 skipped** (1m18s). Failures: `ConversationDatabaseTest` 9 (`SQLITE_CANTOPEN` code 14), `MediaSendingManagerMigrationTest` 6, `IncomingMessageAdmissionTest` 5, `ConversationRepositoryTest` 2 (NPE on a null persisted snapshot), `NostrDirectMessageHandlerTest` 1 (5 s coroutine timeout). A rerun in a plain local clone outside OneDrive gives the same result (24 failed; `IncomingMessageAdmissionTest` flaky 5↔6), so **OneDrive is ruled out**. All failing classes are Robolectric tests backed by the conversation SQLite DB. The root error is `SQLITE_CANTOPEN` inside Robolectric's native runtime (`SQLiteConnectionNatives.nativeOpen`), and the assertion failures are consistent with the DB never opening. Windows/Robolectric native-SQLite limitation. CONFIRMED Windows-only: GitHub Actions run 37591481044 on the same commit `8d7de5e` (ubuntu-24.04) passed `testDebugUnitTest`, `lintDebug`, `assembleDebug` and the reproducible release builds. `lintDebug` PASS (no errors) and `:wear:assembleDebug` PASS (in the clean clone). Building inside the OneDrive folder also hit `AccessDeniedException` locks on `app/build/intermediates`: develop from a non-synced path.
- **Physical-device gate:** `docs/release-gate-runbook.md` + `tools/release_gate/*.py` (ADB-driven, needs ≥3 Android devices, 2 OEMs, an iOS device). Mature, but currently configured for upstream identity.
- **Release identity is upstream's:** `applicationId com.bitchat.droid` (also Wear), the GitHub APK cert pin `BITCHAT_GITHUB_RELEASE_CERT_SHA256` in `gradle.properties`, upstream GitHub release/georelay URLs, fastlane listing text. MeshUp cannot publish under `com.bitchat.droid` (INFERRED: Play applicationIds are unique to their owner).

---

## 6. Reusable components (preserve)
- Wire protocol, Noise XX stack (with external test vectors), Ed25519 announce binding (`AnnouncementIdentityValidator`), bounded decompression. These keep MeshUp **interoperable with BitChat iOS/Android peers**.
- BLE GATT dual-role transport, per-link write queue, `PowerManager` duty-cycling, fragmentation/reassembly limits, gossip sync (GCS).
- `ConversationRepository` + `ConversationStorageCipher` (versioned SQLite, Keystore AES-GCM, crypto-erase, tombstones): the natural base for "database is the source of truth".
- `MessageRouter` (clean MESH/NOSTR/QUEUED policy, injectable clock, test hooks): extend with persistence rather than rewrite.
- `NotificationManager` (MessagingStyle, private lock-screen DM version, inline reply), `LiveLocationPrivacyGate` (location fail-closed), panic wipe, onboarding status managers and screens, localisation, reproducible-build and release-gate tooling.

## 7. Fragile components (change only with tests)
- `BluetoothMeshService` ↔ `MeshCore` duplication: every coordinator fix must be made twice.
- DM identity aliasing across 6 classes plus the dual state stores (`ChatState` vs `AppStateStore`).
- `ChatViewModel` god-object; composables take the concrete VM; no UI tests.
- `BitchatMessage` is both the UI model (Parcelable) and the wire model (`toBinaryPayload`). Adding UI fields risks the protocol.
- Wear literal source-include list; Gson-persisted classes under broad R8 keep rules (renames can corrupt persisted JSON).
- `Application.onCreate` ordering (persistence restore must precede delivery) with silent catches.

## 8. Technical debt (summary)
See `docs/TECHNICAL_DEBT.md`. Headline items: no durable outbox and no ACK-driven retry; dead relay-side store-and-forward; Noise replay-window bug; unbounded per-sender actors; production behaviour wired to debug settings; channel-password stub; Internet/Nostr on at startup; licence-text inconsistency; no navigation or DI; no instrumented tests.

## 9. Documentation vs code mismatches
| Document | Assumption | Reality | Label |
|---|---|---|---|
| `docs/product/04` | UI→VM→Domain→Message Router→Transport Manager; router owns dedup/TTL/retry/acks | No domain layer; router handles private messages only; dedup/TTL/relay are in `mesh/` and duplicated per transport | CONFIRMED |
| `docs/product/04` | Persistent outbox; DB is source of truth; store-and-forward | Outbox in RAM; only DMs persisted; relay-side S&F is dead code | CONFIRMED |
| `docs/product/04` | Battery modes ACTIVE/BALANCED/LOW_POWER/PAUSED | PERFORMANCE/BALANCED/POWER_SAVER/ULTRA_LOW, automatic, no PAUSED | CONFIRMED |
| `docs/product/02` | Delivery states Sending/Queued/Relaying/Delivered/Failed + retry | No Queued/Relaying state, no hop count, no manual retry, no post-restart retry | CONFIRMED |
| `docs/product/02`/`06` | Rooms with public/invite-only/password, expiry, retention | Slash-command channels only; password check is a stub; no expiry/retention/invite-only | CONFIRMED |
| `docs/product/06` | Home/Chats/Rooms/People/Profile; welcome + display-name onboarding | Single `ChatScreen` + ~9 sheets; name auto `anon####`; first screen is a permission check | CONFIRMED |
| `docs/product/05` | Replay resistance, fail closed, minimal metadata, no identifiers in logs | See SECURITY_REVIEW §16: several High/Medium gaps | CONFIRMED |
| `docs/product/08` D007 | Internet optional | Internet optional for core chat, but **Tor + Nostr connect at every process start** by default | CONFIRMED |
| `docs/product/09` | Licence must be reviewed | LICENSE.md is GPLv3; README/PRIVACY_POLICY say public domain | CONFIRMED |
| `docs/product/03` | BitChat has "BLE and Wi-Fi-aware transport" | True, but Wi-Fi Aware is off by default and debug-gated | CONFIRMED |
| `docs/sync.md`, `docs/SOURCE_ROUTING.md`, `docs/client-rewrite-contracts.md` | Constants/TLV/test names | Drift from code (see MESH_ARCHITECTURE §15, §8, §18) | CONFIRMED |
| `PRIVACY_POLICY.md:123` | Messages deleted when app closes | Private conversations persist by default | CONFIRMED |
| `docs/product/01` | Working name "Offline Mesh Messenger" | Product is now "MeshUp"; code/branding entirely "bitchat" | CONFIRMED |

## 10. Open questions
1. **Business model vs GPLv3**: is MeshUp intended to be open source? (Blocks commercial planning.)
2. **Network compatibility**: must MeshUp stay wire-compatible with upstream BitChat clients? (This decides whether relay policy, the TTL clamp, stable-ID rotation and the replay fix can be changed unilaterally, or need iOS/upstream coordination.)
3. **Internet defaults**: should Tor/Nostr/geohash be off until the user opts in? (Product decision; affects D007 and privacy.)
4. Keep the Wear module in scope for MeshUp v1?
5. Keep the Kotlin package `com.bitchat.android` (recommended for now) and change only `applicationId`, labels and branding?
6. Do mesh-received messages still notify when no ViewModel is alive (service-only process)? (Device test.)
7. Is `startServices()` idempotent across the three FGS start paths?
8. Local git remotes/uncommitted state on the developer machine (not inspectable in Phase 0).

## Sources
- Repository paths cited inline (repo-relative). Specialist evidence: `docs/MESH_ARCHITECTURE.md`, `docs/SECURITY_REVIEW.md`.
- Upstream repository and licence metadata: https://github.com/permissionlesstech/bitchat-android
- Android FGS types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android FGS background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android location permissions (background location guidance): https://developer.android.com/training/location/permissions
