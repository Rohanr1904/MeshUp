# MeshUp — Implementation Plan: Phase 2 (Reliability + engine hardening)

> Status: **Decisions recorded 2026-10-07** (D1, D2, D3, D7 set by the product owner; the rest use the proposed defaults; see Decision 015) · Baseline: `main` @ `2c85883` (Phase 1 PR-1..4 merged; PR-5 open)
> Implements: TARGET_ARCHITECTURE R-1 (durable outbox + delivery states), R-5.1/5.2/5.3/5.4/5.5, R-9, plus the Phase 1 Tor-stop follow-up.
> Out of scope: new direct-chat screen and messaging redesign (Phase 2b, after R-1 lands), password rooms (Phase 3 / R-4), store-and-forward (R-7, design only later), R-5.6 media encryption and R-5.7 advertised ID (later), ML Kit replacement (Phase 6).
> Evidence: two read-only inspections on 2026-10-07 (mesh-architect, security-engineer; Opus); key claims re-checked by the chief engineer (marked ✔).

## 1. Facts the plan relies on (CONFIRMED)
Paths are relative to `app/src/main/java/com/bitchat/android/`.

**Reliability**
- ✔ The outbox is RAM-only: `services/MessageRouter.kt:85` (`ConcurrentHashMap`). Limits come from `util/AppConstants.kt:135-139`: 2 s tick, 24 h expiry, 100 per peer, handshake backoff 5/15/30/60 s.
- A message leaves the outbox as soon as a transport accepts it (`MessageRouter.kt:219-223`). There is no ACK wait, ACK timeout or resend anywhere.
- `Sending` rows are restored after a restart and stay `Sending` forever. DROPPED route results are never turned into `Failed`: `ui/ChatViewModel.kt:1018-1023` handles them, but `service/ConversationNotificationReceiver.kt:69` and `ui/CommandProcessor.kt:697` ignore them.
- `onMessageExpired` is a ViewModel lambda (`ChatViewModel.kt:441`), and only UI-owned objects construct the router. So nothing flushes or expires the outbox when no UI is alive.
- Race: `mesh/BluetoothMeshService.kt:1054-1113` silently starts a handshake instead of sending if the session disappears between the route check and the send.
- DB: `services/ConversationRepository.kt`, plain `SQLiteOpenHelper`, `DATABASE_VERSION = 4` (:394).
  - `private_messages.message_id TEXT UNIQUE`.
  - Status is `delivery_type` 0–6.
  - Payload is AES-GCM via `ConversationStorageCipher` (Keystore alias `bitchat_conversation_storage_v1`, AAD `"message:$id"`).
  - Migrations are a stepwise `if (version == N)` ladder (:512-547).
  - Pruning keeps 1000 messages per conversation and 20k in total, and deletes cascade.
  - `clearAll` is at :1086.
- Resending with the same `messageID` is safe on the receiver. `AppStateStore.addPrivateMessageDurably` deduplicates via `seenMessageIds`, the DB uses `CONFLICT_IGNORE` plus tombstones, and a duplicate produces no UI change or notification (`BluetoothMeshService.kt:489-493`). The DELIVERED ACK is re-sent for duplicates (`mesh/MessageHandler.kt:119-122`).
  - Exception: received files get a new UUID on each arrival (`MessageHandler.kt:131`), so files must stay out of the resend path.
- Status updates are monotonic, and a late ACK upgrades `Failed` (`AppStateStore` :432-437).

**Engine hardening**
- ✔ R-5.1 replay window: `noise/NoiseSession.kt:80-92` shifts each byte with `ushr` and carries with `shl`. The direction is reversed (offset k lives in byte k/8, bit k%8). The wire nonce (4-byte big-endian) is not affected.
- ✔ R-5.3 TTL: `mesh/PacketRelayManager.kt:58-70` decrements whatever TTL arrives, with no clamp.
  - Do NOT clamp earlier in the pipeline: ✔ `mesh/SecurityManager.kt:159` treats `ttl == 7` as direct ingress, so an early clamp would let a forged packet pass as direct.
  - Signatures exclude TTL (`docs/SOURCE_ROUTING.md:123`).
- R-5.2 actors: `mesh/PacketProcessor.kt:55`.
  - ✔ The actor map is an unsynchronised `mutableMapOf`, keyed by the unauthenticated header `senderID` and created before validation (:75 vs :119).
  - ✔ Channels are `UNLIMITED` (:47), cleared only at shutdown.
  - `peerActors` (:43) is dead code.
- R-5.4 signing:
  - ✔ `crypto/EncryptionService.kt:227-242`: `sign()` returns an empty array and `verify()` returns `hasEstablishedSession`. Both are dead code; their only callers are unused.
  - The sender fails open, sending unsigned at `BluetoothMeshService.kt:1590-1613`, `:1283-1285` and `:1345-1347`, and at `sync/GossipSyncManager.kt:156,173`.
  - Receivers already require signatures for ANNOUNCE, MESSAGE, FILE_TRANSFER, VOICE_FRAME and LEAVE (`SecurityManager.kt:256-263`). FRAGMENT must stay unsigned (iOS parity, `FragmentManager.kt:153`).
  - Silent identity regeneration on a load or save failure: `EncryptionService.kt:463-500`, `noise/NoiseEncryptionService.kt:85-116`.
- R-5.5 logs:
  - 1,215 `Log.*` calls: v 16, d 404, i 123, w 305, e 367.
  - ✔ There is no `-assumenosideeffects` in `app/proguard-rules.pro`.
  - High-risk lines: the full npub (`nostr/NostrIdentity.kt:32`), all peer and favourite fingerprints (`ui/PrivateChatManager.kt:198-253`, `ui/DataManager.kt:172-194`), fingerprints plus peer ID at WARN (`mesh/PeerFingerprintManager.kt:59`), and nickname plus peer ID (`mesh/PeerManager.kt:258-262,367`).
  - The native Arti library logs `SOCKS5 CONNECT host:port` (`tools/arti-build/src/lib.rs`). R8 cannot strip this.
- R-9:
  - ✔ `READ_MEDIA_IMAGES/VIDEO/AUDIO` (`AndroidManifest.xml:56-58`) have no code use.
  - ✔ `MeshForegroundService` declares the `dataSync` type (:136) but only uses connectedDevice|location.
  - ✔ `BLUETOOTH_SCAN` (:15) lacks `neverForLocation` (Wear already has it).
  - `ACCESS_BACKGROUND_LOCATION` is requested on API 29+.
- Tor stop: `lib.rs:434-465` aborts only the SOCKS accept task. `ARTI_CLIENT` and the per-connection tasks survive.
  - `initialize()` already builds a fresh client on every start (`ArtiProxy.kt:65`), so dropping the old one costs nothing.
  - The `.so` files are checked in and pinned in `tools/arti-build/SHA256SUMS`. CI only verifies the checksums. Rebuilds happen in a pinned linux/amd64 container (`rebuild-in-container.sh`).

## 2. Design decisions for Phase 2
| # | Decision | Reason | Alternative rejected |
|---|---|---|---|
| P2-1 | **Security quick wins first** (R-5.1, R-5.3, R-5.5), then the outbox. | Small, JVM-testable, and they close confirmed vulnerabilities before more engine-adjacent change. | Outbox first: the highest-risk change would run on an unhardened engine. |
| P2-2 | Outbox = new `outbox` table in the existing DB (**migration v4→v5**), keyed by `message_id`, **with no FK to `private_messages`**. The payload uses the same cipher with AAD `"outbox:$id"`. | Pruning cascades would otherwise delete queued messages. Reusing the cipher keeps one key-management path. | Room/ORM (double migration risk); WorkManager as the store (the DB must be the source of truth). |
| P2-3 | The migration backfills existing `Sending` rows authored by us into the outbox. Rows older than the expiry become `Failed`. | Fixes the "Sending forever" state for existing installs. | Leaving them alone: users keep seeing permanent spinners. |
| P2-4 | State machine `Queued → Sent → Delivered → Read`, or `Failed(reason)`. The outbox row is deleted on ACK or on a terminal state. Resends reuse the **same `messageID`** and re-encrypt under the current Noise session. | Receiver dedup already makes same-ID resends safe. Noise owns the nonces. | New IDs per resend: duplicates on the receiver. |
| P2-5 | A **process-level owner** (started with `MeshForegroundService`) rehydrates the outbox only after `AppStateStore` is `Ready`, and owns expiry → `Failed`. The ViewModel stops owning that lambda. | Today nothing runs without a UI. | Keeping it in the ViewModel: messages never expire or flush in the background. |
| P2-6 | **Files and media stay out of the resend path** (text DMs only in Phase 2). | Received files get new UUIDs, so they cannot be deduplicated. | Resending files: duplicates on the receiver. |
| P2-7 | The TTL clamp `min(ttl, 7)` goes in `PacketRelayManager` **before the decrement**, never at ingress. | Preserves the `ttl == 7` direct-ingress semantics in `SecurityManager`. | An ingress clamp would forge "direct" status. |
| P2-8 | Actors become a **fixed striped pool** (hash(peerID) mod 16) with bounded channels (64, drop-oldest), plus a **per-link token bucket** applied before signature verification and decompression. | An LRU could evict a busy actor and create two actors for one peer, breaking ordering. Limits must never key only on a forgeable ID. | LRU map: ordering bugs and still forgeable. |
| P2-9 | Signing **fails closed on the sender only**: drop the send and surface `Failed`; ANNOUNCE skips and retries. Receiver policy is unchanged and FRAGMENT stays unsigned. Delete the stubs. | No interop impact: receivers already drop unsigned required types. | Receiver-side tightening: interop risk with iOS, needs their source. |
| P2-10 | An unreadable identity key is **never silently replaced**: keep the blob and raise an identity-reset event to the UI. A failed save fails loudly. | Silent regeneration breaks verification and contacts invisibly. | Status quo. |
| P2-11 | Release builds strip `Log.v/d/i` via R8 `-assumenosideeffects` (app and wear). Kept `w/e` calls use a `Redact.id()` short-hash helper. | Removes about 540 calls from release, and IDs stop appearing in crash logs. | Manual deletion of 1,215 calls. |
| P2-12 | The Tor stop fix (drop `ARTI_CLIENT`, abort connection tasks, shut down the runtime) is done **only if a Linux/CI rebuild of the `.so` is reproducible**. Until then the Phase 1 restart prompt stays. | A native change must keep reproducible checksums. | Rebuilding on Windows ad hoc breaks reproducibility. |

## 3. Work breakdown (PR-sized, in order)
Each PR goes through the chief engineer's review. **Sec** = security-engineer (Opus) review. **Mesh** = mesh-architect (Opus) review.

### Track A: security quick wins (JVM-verifiable, no wire change)
- **P2-PR1 R-5.1 replay window fix** (`noise/NoiseSession.kt`).
  - Tests:
    - the two `knownDefect_R1_*` tests flip and are renamed
    - shifts of 1/7/8/9/1023/1024
    - the window boundary
    - a forged far-future nonce does not move the window
    - a randomized check against a HashSet model
    - golden nonce bytes
  - Sec.
- **P2-PR2 R-5.3 TTL clamp** (`mesh/PacketRelayManager.kt`).
  - Tests: `knownDefect_R5_3_*` flips (254 → 6); rename the second test, because the "relays 20/20" behaviour is TD-06 relay policy, not this fix.
  - Mesh.
  - Before merge, check that iOS originates TTL ≤ 7.
- **P2-PR3 R-5.5 release log stripping + redaction.** Covers `app/proguard-rules.pro` and `wear/proguard-rules.pro`, a `Redact` helper, and fixes to the high-risk lines in §1.
  - Verify: a dex string scan of the release APK, and the reproducible-build CI stays green.
  - Sec.

### Track B: engine robustness
- **P2-PR4 R-5.2 striped bounded actors + per-link rate limit** (`mesh/PacketProcessor.kt` and the GATT ingress points).
  - Tests: the bound holds under a flood of forged sender IDs; per-peer ordering is preserved; no unbounded growth.
  - Sec + Mesh.
- **P2-PR5 R-5.4a sender fail-closed + delete stubs** (`BluetoothMeshService.kt`, `GossipSyncManager.kt`, `EncryptionService.kt`).
  - Tests: a signing-failure fake produces no broadcast at each call site and marks the message `Failed`; FRAGMENT stays unsigned.
  - Sec.
- **P2-PR6 R-5.4b identity-reset detection + UX**.
  - Covers `EncryptionService`, `NoiseEncryptionService`, a Profile banner and an event.
  - Tests: a corrupt blob raises the event and the blob is kept; a failed save is surfaced.
  - Sec.

### Track C: durable delivery (R-1)
- **P2-PR7 DB v5 migration + `OutboxStore`** (no behaviour change).
  - Covers `ConversationRepository.kt` and `ConversationDatabaseTest`.
  - Tests:
    - fresh install
    - v4 → v5 upgrade
    - backfill of `Sending` rows
    - a corrupted DB
    - `clearAll` wipes the outbox
    - pruning does not touch the outbox
  - These tests fail locally on Windows (TD-29), so **CI is the gate**.
  - Sec + Mesh.
- **P2-PR8 MessageRouter writes before it sends, rehydrates, and gets a process-level owner**.
  - Covers `MessageRouter.kt`, `ChatViewModel.kt`, `ConversationNotificationReceiver.kt`, `CommandProcessor.kt` and `MeshForegroundService.kt`.
  - Also fixes the session-race silent drop and maps DROPPED to `Failed` everywhere.
  - Tests: `knownDefect_TD01_*` flips; restart rehydration.
  - Mesh.
- **P2-PR9 DeliveryTracker: ACK timeout, resend, Failed + retry UI**.
  - Covers a new tracker and `BluetoothMeshService.kt:516`. The legacy chat shows `Failed` with a "Retry" action.
  - Tests: state-machine transitions; a duplicate resend gives one row and one notification; a late ACK upgrades `Failed`.
  - Mesh + Sec.

### Track D: platform + Tor
- **P2-PR10 R-9 manifest compliance.** Remove the `READ_MEDIA_*` permissions and the unused FGS `dataSync` type. Add `neverForLocation`, and limit background location per the owner decision.
  - Emulator: manifest merge, permission prompts and FGS start.
  - **Physical devices:** BLE discovery and background or boot start on API 34–37.
- **P2-PR11 (conditional) Arti native stop fix + lower SOCKS logging.**
  - Rebuild in the pinned container on Linux or a CI job, with a second builder reproducing `SHA256SUMS`.
  - Emulator: OFF → no Tor sockets without a restart; then OFF/ON/OFF re-bootstrap.
  - Remove the restart prompt once it is verified.
  - Sec.

## 4. Do-not-touch in Phase 2
- **Wire formats:** packet layout, nonce encoding, signature coverage, the receiver signature policy, FRAGMENT signing, BLE UUIDs and the advertised ID (R-5.7).
- **Code areas:** the `identity/` storage format, except the reset detection in PR6; `protocol/`; Kotlin package names; existing pref keys; the Keystore alias.
- **Build and release:** `gradle/*.lockfile` and `verification-metadata.xml` (no new dependencies), CI workflows (except a native-rebuild job, if PR11 is approved), and the release signing and reproducible-build scripts.

## 5. Phase 2 exit criteria
1. All `knownDefect_*` characterization tests are inverted (R1, R-5.3, TD-01) and green. New tests are green. The existing suite is no worse than baseline (TD-29 set only, confirmed on CI).
2. The v4→v5 migration is tested for fresh install, upgrade and corruption on CI.
3. A queued DM survives process death and is delivered **exactly once** after the peer reappears. This is **verified on 2+ physical devices** (Decision 009). The emulator and JVM cover the logic only.
4. No `Sending` row survives indefinitely: each one ends as Delivered, Read or Failed (with retry).
5. A release APK contains no `Log.d/i` template strings and no full fingerprints or npubs in the kept logs. The reproducible release build is still byte-identical on CI.
6. The manifest is free of unused permissions and FGS types. BLE discovery still works on physical devices after `neverForLocation`.
7. `:app:assembleDebug`, `:wear:assembleDebug` and `lintDebug` pass.

## 6. Product-owner decisions (recorded 2026-10-07; Decision 015)
| # | Question | Decision |
|---|---|---|
| D1 | How long a queued message waits before it becomes `Failed` | **1 h** (owner; was 24 h) |
| D2 | Per-peer queue limit | **200** (owner); overflow becomes `Failed("queue full")` |
| D3 | ACK-timeout resend schedule and max attempts | **30 s, 1 m, 2 m, 2 m, then Failed** (owner; 4 resends) |
| D4 | Do Nostr-routed messages get ACK resends? | **No** in Phase 2 (mesh only); revisit with R-7 |
| D5 | Show `Failed` with a Retry action? | **Yes** |
| D6 | Signing failure surfaces as "send failed"? | **Yes** |
| D7 | Identity reset UX | **Warn with a banner plus re-verify guidance; don't block. Any deliberate identity reset requires the user to type `reset` to confirm** (owner) |
| D8 | Drop background location on API 31+ (relying on `neverForLocation`), accepting reduced background discovery until verified on devices? | **Yes, behind a device test**; keep it for API 26–30 |
| D9 | Keep in-app APK update/download and hotspot sharing in a future Play build? | **Decide before Phase 9**; no change in Phase 2 |
| D10 | Tor native fix in Phase 2 (needs a Linux host or a CI rebuild job)? | **Yes, if a CI rebuild job is acceptable**; otherwise defer and keep the restart prompt |
| D11 | Release logging level | **Only w/e, redacted**; no diagnostics export in Phase 2 |

D4–D6 and D8–D11 use the proposed defaults. Consequence of D1 + D3: a message for an absent peer waits up to 1 h; once handed to the mesh, it is resent at +30 s, +1 m, +2 m and +2 m (about 5.5 min in total) before it becomes `Failed`, which the user can Retry (D5).

## 7. Risks
| Risk | Mitigation |
|---|---|
| A migration bug corrupts DM history | Additive table only, `IF NOT EXISTS`, backfill in a transaction; upgrade and corruption tests on CI; no downgrade path is claimed |
| Resend causes duplicates | Same `messageID`; receiver dedup is already proven; files are excluded (P2-6); a dedicated test |
| Concurrency regressions from the striped actors | Ordering tests; the rate-limit numbers start conservative and are tuned on devices |
| R8 stripping breaks the reproducible build or hides needed diagnostics | CI byte comparison stays the gate; w/e calls kept and redacted |
| `neverForLocation` reduces discovery on some OEMs | Gated by D8 and a physical-device test; easy to revert |
| No physical devices available | Track A and most of Track B close on JVM; **exit criteria 3 and 6 stay open until devices exist**; nothing is claimed without them |
| Windows TD-29 hides DB test regressions | Track C uses CI as the gate; DB tests run on Ubuntu |

## 8. Agent/model routing
- Implementation: android-engineer (Sonnet) for PR3, PR6, PR8 (UI parts), PR9 (UI) and PR10. The engine-adjacent PRs (PR1, PR2, PR4, PR5, PR7, PR8, PR9) also go to android-engineer, but with a tighter brief and mandatory Opus review.
- Reviews: security-engineer (Opus) and mesh-architect (Opus), as marked. The chief engineer reviews every diff.
- QA: full-suite runs and baseline comparison after each PR; the CI result is authoritative for DB tests.
- Estimated size: **11 PRs** (10 if PR11 is deferred). Phase 2b (messaging UX: native direct chat, people and conversations list) is planned after PR9.
