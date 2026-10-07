# MeshUp — Technical Debt Register

> Last updated: 2026-10-07 · Baseline `8d7de5e` · Evidence-based. A file's size alone is never counted as debt; each item cites concrete coupling, defect or absence evidence.
> Detail: `docs/MESH_ARCHITECTURE.md` (§20), `docs/SECURITY_REVIEW.md` (§18 risk register), `docs/PROJECT_ANALYSIS.md`.
> Verification: static reading only. Nothing below was confirmed by a build, test run or device.

Severity: **H** blocks a product requirement or is a security/data-loss risk · **M** slows work or causes bugs · **L** hygiene.

---

## A. Confirmed technical debt

| ID | Item | Sev | Evidence | Product impact |
|---|---|---|---|---|
| TD-01 | **No durable outbox.** Queued DMs live in `MessageRouter.outbox` (RAM). DB rows written as `Sending` are never re-queued or failed after restart. The expiry callback is a single VM-owned lambda. | H | `services/MessageRouter.kt:84, :212-214`; `ui/PrivateChatManager.kt:~174`; `ui/ChatViewModel.kt:441` | Breaks "messages survive lifecycle changes" and "DB is source of truth" |
| TD-02 | **No ACK-driven retry.** Delivery ACK is single-shot. The sender removes the message from the outbox once it is handed to a transport. Lost fragments are never retransmitted. | H | `mesh/MessageHandler.kt:237-269`; `MessageRouter.kt:212`; `mesh/FragmentManager.kt` (30 s timeout) | No reliable delivery semantics |
| TD-03 | **Relay-side store-and-forward is dead code.** `cacheMessage()` has no callers, skips encrypted packets, and decodes the recipient with `String(bytes)` instead of hex. | H | `mesh/StoreForwardManager.kt:54-99` | The "store-and-forward" differentiator does not exist yet |
| TD-04 | **Noise replay window shifts the wrong way.** Older nonces become replayable. No test covers it. | H | `noise/NoiseSession.kt:~49-104` (SECURITY R1) | Security requirement #4 |
| TD-05 | **Unbounded per-sender actors** created before validation; the actor map is unsynchronized and never pruned. | H | `mesh/PacketProcessor.kt:~40-75` (R2) | DoS and memory exhaustion at events |
| TD-06 | **Unsigned relayed types + unsigned, unclamped TTL**; TTL ≥ 4 is always relayed. | H | `mesh/SecurityManager.kt:~256-265`; `mesh/PacketRelayManager.kt:58-70, 142-171` (R3) | Flooding/amplification, battery drain |
| TD-07 | **Duplicated coordinator.** `BluetoothMeshService` re-implements `MeshCore`; only Wi-Fi Aware implements `MeshTransport`. Each transport has its own `EncryptionService`, so a peer reachable on both radios needs two Noise sessions. | M | `mesh/BluetoothMeshService.kt:250-1621` vs `mesh/MeshCore.kt`; `mesh/UnifiedMeshService.kt:104-117` | Every mesh fix is done twice; risk of the two drifting apart |
| TD-08 | **Production behaviour wired to debug settings** (BLE on/off, relay on/off, connection limits, gossip capacity, Wi-Fi Aware). The debug sheet is reachable by end users from About. | M | `PacketRelayManager.kt:24`; `BluetoothConnectionManager.kt:160-216`; `BluetoothMeshService.kt:160-170`; `ui/debug/*` | Users can break the mesh; settings are not product-owned |
| TD-09 | **Channel password verification stub** returns `true` (`// TODO: REMOVE THIS - FOR TESTING ONLY`). PBKDF2 salt = channel name. | H | `ui/ChannelManager.kt:125-128, 130+` | "Password rooms" would be misleading |
| TD-10 | **God ViewModel + no DI.** `ChatViewModel` constructs 9 collaborators, holds both concrete `BluetoothMeshService` and `MeshService`, and references globals on 78 lines. Six sheets take the concrete VM. Zero Compose tests. | M | `ui/ChatViewModel.kt:135-206, 1107, 1589` | Blocks the new navigation/UX without regressions |
| TD-11 | **Dual state stores** (`ChatState` vs `AppStateStore`), with handler code to "avoid double-adding to UI state". DM identity aliasing is spread over 6 classes. | M | `ui/ChatState.kt`; `services/AppStateStore.kt`; `ui/MeshDelegateHandler.kt` | Stale/duplicate UI state |
| TD-12 | **No navigation.** `navigation-compose` is declared but unused; one `ChatScreen` + ~9 sheets. | M | `app/build.gradle.kts`; `ui/ChatScreen.kt:836-937` | UX spec (Home/Chats/Rooms/People/Profile) needs a shell |
| TD-13 | **UI model == wire model.** `BitchatMessage` is Parcelable and serializes the wire payload. | M | `model/BitchatMessage.kt` | Adding room/expiry/hop fields risks the protocol |
| TD-14 | **Silent failure + sleeps on startup.** More than 10 swallowed `catch (_: Exception)` in `BitchatApplication`; `delay(500/1000/500)` in onboarding. | M | `BitchatApplication.kt`; `MainActivity.kt` | Undiagnosable startup failures |
| TD-15 | **Negotiated BLE MTU ignored**; 512-byte frames assumed. | M | `mesh/BluetoothGattClientManager.kt:501-520`; `AppConstants.Fragmentation` | Possible truncation on low-MTU peers |
| TD-16 | **Fragment cap vs UI file limit:** 256 fragments ≈ 115 KB versus a ~9.87 MB UI limit. | M | `mesh/FragmentingPacketSender.kt:121-153`; `AppConstants.Media` | Large media fails |
| TD-17 | **Logging hygiene:** ~1,196 `Log.*` calls, no release stripping; peer IDs, fingerprints and the favourites set are logged. | M | `app/proguard-rules.pro`; `ui/PrivateChatManager.kt:~230`; `ui/DataManager.kt:~161-197` (R9) | Violates security requirement on logs |
| TD-18 | **Identity hygiene:** two Ed25519 keys, stub `sign()/verify()`, silent identity regeneration, legacy plaintext key may persist, sender fails open (sends unsigned). | M | `crypto/EncryptionService.kt:~226-241, ~463-524`; `BluetoothMeshService.kt:1590-1612` (R12–R14) | Fail-closed requirement |
| TD-19 | **At-rest gaps:** received media plaintext; conversation metadata plaintext; extraction rules cover only 2 legacy pref files. | M | `features/file/FileUtils.kt:~238-261`; `res/xml/data_extraction_rules.xml` (R10–R11) | Lost-device threat |
| TD-20 | **Internet on by default:** Tor + Nostr relay connect + `#p` subscription at every process start; no master offline switch. | M | `BitchatApplication.kt`; `nostr/NostrBackgroundRuntime.kt:~47-60`; `net/TorPreferenceManager.kt` (R17) | Privacy and battery; needs a product decision |
| TD-21 | **Manifest/permission surface:** `ACCESS_BACKGROUND_LOCATION` used only for the FGS-type eligibility check; unused `READ_MEDIA_*`; FGS `dataSync` declared but unused; `BLUETOOTH_SCAN` without `neverForLocation`; boot auto-start default on with no UI toggle. | M | `AndroidManifest.xml`; `service/MeshForegroundService.kt`; `service/MeshServicePreferences` | Play review risk, onboarding friction |
| TD-22 | **Settings fragmentation:** more than 12 SharedPreferences files with separate managers; `lateinit prefs` initialisation-order hazards. | L | `grep getSharedPreferences`; `MeshServicePreferences.prefs` | Bugs during settings/privacy work |
| TD-23 | **Dead/misleading code:** `MessageRetentionService` (218 commented lines), unhandled `/save` and `/transfer`, unused Nordic BLE dependency, unused `tor-android-binary` catalog entry, stale `RealTorProvider` keep rule, dead FRAGMENT gossip hook, unused `AppConstants.Power` constants, ByteArray reference comparison. | L | see MESH_ARCHITECTURE §20, PROJECT_ANALYSIS §8 | Noise and confusion |
| TD-24 | **Naming/layout:** `wifi-aware/` dir vs `wifiaware` package (tests in both), `service/` vs `services/`, `util/` vs `utils/`. | L | tree | Friction |
| TD-25 | **Test gaps:** no `androidTest`, no Compose tests; untested `GossipSyncManager`, `StoreForwardManager`, `MeshCore`, `UnifiedMeshService`, GATT managers, Wi-Fi Aware core, FGS/boot, replay window; crypto/identity have 1 test each. | H | `app/src/test` inventory | Any mesh/UX refactor is unprotected |
| TD-26 | **Doc drift:** `docs/sync.md` constants, `docs/SOURCE_ROUTING.md` TLV count byte, `docs/client-rewrite-contracts.md` cites 3 missing tests, `checkChangedLineCoverage` task missing, `PRIVACY_POLICY.md` says messages vanish on close. | L | MESH_ARCHITECTURE §15/§18; SECURITY §12 | Misleads contributors and users |
| TD-27 | **Licence text inconsistency:** `LICENSE.md` GPLv3 vs README/PRIVACY_POLICY "public domain". | H (release) | `README.md:30`; `PRIVACY_POLICY.md:156`; git `fb2bb64` | Release gate (Decision 010) |
| TD-28 | **Upstream release identity baked in:** `applicationId com.bitchat.droid` (app and Wear), GitHub cert pin, upstream release/georelay URLs, fastlane text. | M | `app/build.gradle.kts`; `gradle.properties`; `nostr/RelayDirectory.kt`; `util/UniversalApkManager.kt` | Cannot ship without re-identity |

### A.1 Characterization coverage (Phase 0.5, 2026-10-07)
Pinned by JVM tests that pass today and must be inverted by the fix: TD-01 → `services/MessageRouterRestartCharacterizationTest`; R1 replay → `noise/NoiseSessionReplayWindowCharacterizationTest`; ingress TTL → `mesh/PacketRelayTtlCharacterizationTest`. Each `knownDefect_*` test documents the correct expectation in its KDoc.

| TD-29 | **Robolectric SQLite tests fail on Windows.** 23–24 tests in 5 DB-backed classes fail locally with `SQLITE_CANTOPEN` from Robolectric's native runtime; `IncomingMessageAdmissionTest` is flaky by one. | M | `app/src/test/.../ConversationDatabaseTest.kt` and 4 dependent classes | Windows developers cannot get a green local suite; real regressions hide in the noise. CONFIRMED Windows-only: GitHub Actions run 37591481044 on the same commit `8d7de5e` (ubuntu-24.04) passed `testDebugUnitTest`, `lintDebug`, `assembleDebug` and the reproducible release builds. |

## B. Suspected debt (needs a test or device to confirm)
- Broadcast storms in dense rooms (>30 nodes): TTL ≥ 4 is relayed unconditionally and DMs are flooded. Needs a Mesh Lab run.
- Bridged own broadcasts arriving with TTL 6 confuse "direct ingress" heuristics (`TransportBridgeService` + `SecurityManager` TTL==7 checks).
- Message loss when a Noise session drops between `MessageRouter.isReady()` and the send (`MeshCore.sendPrivateMessage` drops without a session).
- Replay of signed broadcast MESSAGE after the 5-min dedup expiry or a restart.
- `runBlocking` inside packet actors (`BluetoothMeshService.kt:571, 575`) stalls under load.
- FGS start from boot on API 34–37 with `location` type; OEM battery killers; notifications when no ViewModel is alive.
- 30 s reassembly timeout versus slow multi-hop large transfers.

## C. Architectural risk (not debt per se)
- **Wire compatibility with upstream BitChat** constrains fixes to relay policy, TTL, advertised IDs and the replay window. These need upstream/iOS coordination, or an explicit decision to fork the network.
- **GPLv3** governs all distribution of this codebase.
- **Wear module** compiles app sources in place, so package moves are risky.
- **Upstream keeps moving** (877 commits; weekly georelay updates). The more MeshUp diverges, the more expensive merges become. Prefer additive layers over invasive edits.
- **`security-crypto` deprecated**: a future storage migration is needed, and it must not regenerate identities.

## D. Deferred improvements (do not start in Phase 1)
- Unify BLE onto `MeshCore`/`MeshTransport` (worth doing, but high-risk; only after characterization tests exist).
- Ephemeral/rotating advertised identifiers (protocol-level; needs cross-client design).
- Wi-Fi Aware bulk path without 512-byte fragmentation; PSK derivation.
- Kotlin package rename away from `com.bitchat.android`.
- Replace NanoHTTPD; migrate off EncryptedSharedPreferences.
- Proper NIP-44 v2 for Nostr DMs.
