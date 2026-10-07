# MeshUp — Mesh Architecture (as inherited)

> Phase 0 deliverable. Describes how the inherited BitChat Android mesh **actually** works today. It is not a target design (see `docs/TARGET_ARCHITECTURE.md`).
> Last updated: 2026-10-07. Baseline commit: `8d7de5e` (upstream `permissionlesstech/bitchat-android` merged at `6803d6d`, 2026-10-06).

Status: evidence-based, read-only analysis of `main`. Author: mesh-architect specialist; reviewed by the chief engineer, who re-checked §11 relay rules, §12 dead store-and-forward and §12 in-memory outbox directly in code.
Scope: `app/src/main/java/com/bitchat/android/{mesh,wifi-aware,protocol,sync,model,service,services/MessageRouter.kt,services/meshgraph,hotspot}`.
Verification level: **code reading + unit-test inventory only**. No build, no emulator, no physical-device runs were performed for this document.

Legend: **CONFIRMED** = seen in code/test (path cited). **INFERRED** = reasoned from cited code; not executed. **UNKNOWN** = not determinable from the repo.

---

## 1. Summary

- **CONFIRMED** Two local radio transports: BLE GATT mesh (default ON) and Wi-Fi Aware NAN + TCP sockets (default **OFF**, debug-pref gated). Internet fallback is Nostr (out of scope here, but `MessageRouter` routes to it). `BitchatApplication.kt:59-60`, `ui/debug/DebugPreferenceManager.kt:116` (`getWifiAwareEnabled(default=false)`).
- **CONFIRMED** Routing is **TTL-limited flooding** (default TTL 7) with probabilistic relay suppression only when TTL < 4, plus an **optional v2 source route** for addressed packets computed from gossiped neighbor lists. `mesh/PacketRelayManager.kt:40-171`, `mesh/MeshCore.kt:1044-1068`.
- **CONFIRMED** All mesh state is **in memory**: dedup sets, fragment buffers, gossip store, store-and-forward cache, the private-message outbox (`services/MessageRouter.kt:84`). Only message *history* is persisted (SQLite in `services/ConversationRepository.kt`). A process kill loses every queued/undelivered outbound message.
- **CONFIRMED** The "store-and-forward" component (`mesh/StoreForwardManager.kt`) is **effectively dead**: `cacheMessage()` has zero callers in `app/src/main`, and it would reject `NOISE_ENCRYPTED` (i.e., every private message) anyway (`StoreForwardManager.kt:54-61`).
- **CONFIRMED** Delivery ACKs exist (Noise payload `DELIVERED 0x03`) and are sent automatically by the receiver, but **senders never retry on missing ACK**; the outbox only holds messages that could not be handed to a transport. `mesh/MessageHandler.kt:237-269`, `services/MessageRouter.kt:201-226`.
- **CONFIRMED** There *is* a transport interface (`mesh/MeshTransport.kt`), but only Wi-Fi Aware implements it (`wifi-aware/WifiAwareMeshService.kt:1613`). BLE (`mesh/BluetoothMeshService.kt`, 1,660 lines) re-implements the coordinator logic that `mesh/MeshCore.kt` (1,092 lines) provides for Wi-Fi Aware — two parallel copies of the protocol coordinator.
- **CONFIRMED** Cross-transport bridging is a process-global singleton (`service/TransportBridgeService.kt`) that re-broadcasts packets from one transport to all others with its own dedup (4,096 entries / 5 min) and TTL decrement.
- **CONFIRMED** Battery policy is a single `PowerManager` singleton that derives a profile from foreground/background, battery band, charging and "has direct peers", and drives BLE scan duty cycle, advertise mode, RSSI threshold, announce interval and Wi-Fi Aware keepalives. `mesh/PowerManager.kt:288-381`.
- **CONFIRMED** Many production protocol knobs (BLE on/off, relay on/off, connection limits, gossip capacity, GCS size, Wi-Fi Aware on/off) are read from `ui/debug/DebugSettingsManager` / `DebugPreferenceManager`.

---

## 2. Transport architecture

### 2.1 Layering as implemented
```
UI / ChatViewModel
  -> services/MessageRouter          (mesh vs Nostr vs in-memory outbox; private msgs only)
  -> mesh/UnifiedMeshService         (MeshService facade; picks BLE vs Wi-Fi per call)
       -> mesh/BluetoothMeshService  (BLE coordinator; own Security/Fragment/Peer/Store-Forward/Gossip instances)
       -> wifi-aware/WifiAwareMeshService -> mesh/MeshCore (coordinator) -> MeshTransport (WifiAwareTransport)
  <-> service/TransportBridgeService (global relay bridge between registered TransportLayers "BLE", Wi-Fi)
```
- **CONFIRMED** `mesh/MeshService.kt` is the transport-agnostic facade consumed by UI/routing (send message/private/receipts/files/voice, handshake, peer info).
- **CONFIRMED** `mesh/MeshTransport.kt` is a packet-level interface (`broadcastPacket`, `sendPacketToPeer`, `sendPacketToLink`, `cancelTransfer`, address mapping). It has no `start/stop/discover/connect` — lifecycle is owned by each service.
- **CONFIRMED** `MeshCore(...)` is constructed only in `wifi-aware/WifiAwareMeshService.kt:154`. BLE has its own copy of: `setupDelegates`, `sendPrivateMessage`, `sendReadReceipt`, `applyRouteIfAvailable`, `signPacketBeforeBroadcast`, etc. (`mesh/BluetoothMeshService.kt:250-1621` vs `mesh/MeshCore.kt:216-1092`).
- **CONFIRMED** Each transport service creates its **own `EncryptionService`** (`BluetoothMeshService.kt:51`, `WifiAwareMeshService.kt` "Core crypto/services"), both deriving `myPeerID = identityFingerprint.take(16)`. INFERRED: Noise sessions are per transport; a peer reachable over both radios needs two handshakes and `UnifiedMeshService` has to ask "ready on BLE?" / "ready on Wi-Fi?" separately (`mesh/UnifiedMeshService.kt:104-117, 464-480`).

### 2.2 Transport selection (`mesh/UnifiedMeshService.kt`)
- **CONFIRMED** Public broadcast: BLE if BLE enabled, else Wi-Fi (`:97-102`). Wi-Fi peers then receive public traffic via the bridge.
- **CONFIRMED** Private: BLE if BLE-connected+session; else Wi-Fi if connected+session; else BLE if BLE-connected or (BLE enabled and Wi-Fi not connected); else Wi-Fi (`:104-117`).
- **CONFIRMED** Periodic announce scheduler: only while `profile.hasDirectPeers`; interval from power profile (`:77-95`).

### 2.3 Bridge (`service/TransportBridgeService.kt`)
- **CONFIRMED** `register("BLE", ...)` in `BluetoothMeshService` init when BLE enabled; Wi-Fi registers similarly.
- **CONFIRMED** `broadcast(sourceId, packet)` sends to every *other* registered transport; drops TTL==0; dedups on `"$kind:${logicalPacketId}"` in an access-ordered LRU (`MAX_SEEN_PACKETS=4096`, TTL 5 min, `:22-23, :203-...`); forwards with `ttl-1`.
- **CONFIRMED** `broadcastAndReport` releases the dedup reservation if no transport accepted, so read-receipt retries can use a reconnecting transport.
- INFERRED: a locally originated BLE broadcast reaches Wi-Fi-only peers with TTL 6 (bridge decrements on first crossing). Several code paths treat `ttl == MESSAGE_TTL_HOPS` as "direct ingress" (`mesh/SecurityManager.kt` handshake `isDirectIngress`, fresh-announce exception at `:84`), so bridged-own packets are classified as relayed. Not observed on device.

---

## 3. BLE

### 3.1 Roles and GATT
- **CONFIRMED** Dual role: every node runs a GATT **server/peripheral** (advertise + notify) and a GATT **client/central** (scan + connect + write). `mesh/BluetoothConnectionManager.kt` orchestrates `BluetoothGattServerManager` and `BluetoothGattClientManager`; each role can be toggled in debug settings.
- **CONFIRMED** UUIDs (`util/AppConstants.kt:26-30`): service `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C`, characteristic `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D`, CCCD `0x2902`. Characteristic properties READ|WRITE|WRITE_NO_RESPONSE|NOTIFY (`BluetoothGattServerManager.kt:330-335`).
- **CONFIRMED** Client writes use `WRITE_TYPE_NO_RESPONSE` (`BluetoothPacketBroadcaster.kt:548-551`); server uses `notifyCharacteristicChanged`.
- **CONFIRMED** One GATT value == one complete encoded `BitchatPacket`; the receiving side decodes immediately and derives `peerID` from `packet.senderID` (`BluetoothGattServerManager.kt:257-260`, `BluetoothGattClientManager.kt:570-573`). There is no byte-stream reassembly below the protocol's own fragmentation.

### 3.2 MTU
- **CONFIRMED** Client requests MTU 517 ~200 ms after connect; service discovery starts only after `onMtuChanged` success; MTU failure ⇒ disconnect (`BluetoothGattClientManager.kt:472-520`).
- **CONFIRMED** The negotiated `mtu` value is **never stored or used** (no reference outside the callback signature). Fragmentation assumes a fixed 512-byte frame (`AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD=512`).
- INFERRED: if a peer accepts a smaller MTU (e.g., 185/247), frames up to 512 bytes may be truncated or rejected by the stack. `docs/device-transport-test-matrix.md` lists MTU 23/247/517 checks as unchecked device tasks.

### 3.3 Advertising and scanning
- **CONFIRMED** Advertise data: service UUID only, no name/TX power; **scan response carries service data = 8-byte peerID** (`BluetoothGattServerManager.kt:387-405`). peerID = first 8 bytes of SHA-256(Noise static pubkey) per `docs/NOISE_PEER_ID_BINDING.md`, i.e., stable across sessions until identity reset. INFERRED privacy impact: a passive scanner can track a device across BLE MAC rotations.
- **CONFIRMED** Scan uses a ScanFilter on the service UUID (`BluetoothGattClientManager.kt:211-213`); scan settings and advertise settings come from `PowerManager.getScanSettings()/getAdvertiseSettings()`.
- **CONFIRMED** Scan-result gating (`BluetoothGattClientManager.kt:371-459`): skip if peerID in service data is already connected; skip if RSSI < power-mode threshold (-95/-85/-75/-65 dBm for PERFORMANCE/BALANCED/POWER_SAVER/ULTRA_LOW, `PowerManager.kt` `getRSSIThreshold`); skip if connected/pending/attempt-throttled; skip if client/overall limit reached.
- **CONFIRMED** Scan failure handling with error-specific backoff (`scheduleScanRestart`, base delay, x3 for out-of-resources, 10 s for too-frequently); permanent error 4 not retried. A watchdog runs only for continuous scanning.

### 3.4 Connection limits
- **CONFIRMED** Defaults: overall 8, server 8, client 8 (`ui/debug/DebugSettingsManager.kt:60-76`; `PowerManager.BleSchedule.maxConnections=8`). All power modes keep 8 (`AppConstants.Power.MAX_CONNECTIONS_*` = 8, unused elsewhere).
- **CONFIRMED** `enforceStrictLimits()` evicts per `connectionTracker.getConnectionsToEvict(...)` whenever limits change (`BluetoothConnectionManager.kt:189-216`).
- **CONFIRMED** Connection retry constants: `CONNECTION_RETRY_DELAY_MS=5000`, `MAX_CONNECTION_ATTEMPTS=3` (`AppConstants.kt:20-21`).

### 3.5 Send path / queueing
- **CONFIRMED** `BluetoothPacketBroadcaster` serializes broadcasts through a coroutine actor and keeps a per-link FIFO because Android allows one outstanding GATT op per link: `MAX_PENDING_SENDS_PER_LINK=256`, `MAX_PENDING_BYTES_PER_LINK=1 MiB`, callback-failure retries `MAX_CALLBACK_RETRIES=3`, start-retry delay 15 ms (`:54-57, :511-645`).
- **CONFIRMED** Targeting: originator with source route ⇒ only first hop; addressed packet with directly connected recipient ⇒ only that link; otherwise all links except the ingress `relayAddress` (`:342-470`).
- **CONFIRMED** `broadcastPacket()`'s per-fragment callback always returns `true` (`:176-185`), so fragment progress reflects "enqueued", not "written".

### 3.6 Nordic BLE library
- **CONFIRMED** `implementation(libs.nordic.ble)` (`no.nordicsemi.android:ble:2.11.0`) is declared in `app/build.gradle.kts:167` / `gradle/libs.versions.toml:42,122`, but **no `no.nordicsemi` import exists in `app/src`**. The stack uses raw `android.bluetooth` APIs.

---

## 4. Wi-Fi Aware (`wifi-aware/`)

- **CONFIRMED** Disabled by default (`DebugPreferenceManager.getWifiAwareEnabled(false)`), requires Android 10+ (`WifiAwareSupport.kt` returns unsupported below Q) and `FEATURE_WIFI_AWARE`; also checks location enabled (`WifiAwareController.kt:210-218`).
- **CONFIRMED** Discovery: publish + subscribe on service name `"bitchat"`, with `serviceSpecificInfo = myPeerID` bytes (`WifiAwareMeshService.kt:368-442`). INFERRED: same stable-identifier exposure as BLE scan response.
- **CONFIRMED** Data path: `WifiAwareNetworkSpecifier` with **hard-coded PSK `"bitchat_secret"`** (`:70, :835-837, :1048-1049`), IPv6 link-local TCP sockets, server socket per peer, client connect timeout 7 s, 3 socket attempts, then "role reversal" (`ROLE_SERVER:` message) after 3 client failures (`:66-79, :755-790`). INFERRED: the PSK is public in source so the NDP layer gives no confidentiality; confidentiality relies on Noise above it.
- **CONFIRMED** Framing: `SyncedSocket` = 4-byte big-endian length + payload; 0-length frame is keepalive; 64 KiB max frame; read timeout 90 s (`SyncedSocket.kt`).
- **CONFIRMED** Wi-Fi uses the same `FragmentingPacketSender` + BLE-sized `FragmentManager` (`WifiAwareMeshService.kt:187, :279-286`). INFERRED: Wi-Fi bulk throughput is capped by 512-byte fragments and a 20 ms inter-fragment delay despite 64 KiB socket frames.
- **CONFIRMED** Keepalive/maintenance/discovery refresh cadences come from `PowerManager.profile.wifiAware` (`WifiAwareMeshService.kt:636, :652, :1167`).
- **CONFIRMED** Hotspot coexistence: `WifiAwareController.acquireHotspotLease()` holds Aware down while the Wi-Fi Direct APK-sharing hotspot runs (`WifiAwareController.kt:37-161`). Matches Android guidance that Aware may be unavailable while Wi-Fi Direct/SoftAP is in use (developer.android.com, see Sources).
- UNKNOWN: number of simultaneous NDPs supported on target devices; code does not query `getAvailableAwareResources()` (grep: no hit).

### Hotspot (`hotspot/`)
- **CONFIRMED** Not a mesh transport. Wi-Fi Direct (P2P) group + local HTTP server (`ApkWebServer.kt`) + QR code for **offline APK sharing**, "based on Briar's implementation" (`HotspotManager.kt` header).

---

## 5. Protocol / framing (`protocol/BinaryProtocol.kt`)

- **CONFIRMED** Outer header (big-endian): `version(1) type(1) ttl(1) timestamp_ms(8) flags(1) payloadLen(2 for v1 | 4 for v2)` ⇒ 14 B (v1) / 16 B (v2). Then `senderID(8)`, `recipientID(8, if HAS_RECIPIENT)`, `route (v2 only, if HAS_ROUTE): count(1)+N*8`, `payload`, `signature(64, if HAS_SIGNATURE)`.
- **CONFIRMED** Flags: `0x01 HAS_RECIPIENT, 0x02 HAS_SIGNATURE, 0x04 IS_COMPRESSED, 0x08 HAS_ROUTE` (v2 only).
- **CONFIRMED** Versions accepted on decode: 1 and 2 only. Default outbound v1; v2 when a route is attached or for FILE_TRANSFER (`BluetoothMeshService.kt:919`).
- **CONFIRMED** Message types: `ANNOUNCE 0x01, MESSAGE 0x02, LEAVE 0x03, NOISE_HANDSHAKE 0x10, NOISE_ENCRYPTED 0x11, FRAGMENT 0x20, REQUEST_SYNC 0x21, FILE_TRANSFER 0x22, VOICE_FRAME 0x29`. Noise inner types: `PRIVATE_MESSAGE 0x01, READ_RECEIPT 0x02, DELIVERED 0x03, VOICE_FRAME 0x08, VERIFY_CHALLENGE 0x10, VERIFY_RESPONSE 0x11, FILE_TRANSFER 0x20, PEER_STATE 0x21` (`model/NoiseEncrypted.kt:20-29`).
- **CONFIRMED** Compression: payload DEFLATE if `CompressionUtil.shouldCompress` (threshold 100 B); compressed payload prefixed with original size (2 B v1 / 4 B v2). Decoder bounds expansion to `MAX_PAYLOAD_LENGTH = 10,485,760`. Relays reuse the originator's wire bytes (`WirePayload`) to keep signatures valid across different DEFLATE encoders.
- **CONFIRMED** Signing preimage = packet re-encoded with `signature=null`, `ttl=0` (`SYNC_TTL_HOPS`), originator's wire payload, route included (`BitchatPacket.toBinaryDataForSigning`). Ed25519.
- **CONFIRMED** Padding: PKCS#7-style to 256/512/1024/2048 (+16 B allowance), only if pad ≤ 255 bytes (`protocol/MessagePadding.kt`). On BLE only `NOISE_ENCRYPTED`/`NOISE_HANDSHAKE` are padded (`mesh/BLEPacketPaddingPolicy.kt`). Decoder tries raw first then unpadded.
- **CONFIRMED** Peer IDs on the wire: 8 bytes; broadcast recipient = `0xFF x8`.

## 6. Fragmentation (`mesh/FragmentManager.kt`, `mesh/FragmentingPacketSender.kt`, `model/FragmentPayload.kt`)

- **CONFIRMED** Trigger: unpadded encoded packet > 512 B. Fragment payload header 13 B (8-byte random fragmentID, index, total, original type). Per-fragment data = `min(469, 512 - overhead)` where overhead counts header(13/15), sender, recipient, route, 13-byte fragment header, 16 B padding slack (`FragmentManager.kt:63-140`).
- **CONFIRMED** Fragments inherit TTL, sender, recipient, timestamp and **route** (v2 if routed) and are **unsigned** (`signature = null`).
- **CONFIRMED** Outbound cap: `FragmentingPacketSender.packetsForTransport` caps every packet at `MAX_FRAGMENTS_PER_ID = 256` (`FragmentingPacketSender.kt:121-153`) even though `createFragments(packet)` defaults to 0xFFFF. 20 ms inter-fragment delay; cancellable per `transferId`.
- INFERRED: practical max encoded packet ≈ 256 × ~460 B ≈ 115–118 KB, while the UI file limit is ~9.87 MB (`AppConstants.Media.MAX_FILE_SIZE_BYTES`). Private media surfaces a 256-fragment error (`mesh/PrivateMediaTransfer.kt:102,202,207`); for public files the send fails via `TransferProgressManager.fail`. UX path for oversized public files not verified.
- Minor CONFIRMED: `FragmentManager` assumes header 13/15 B while `BinaryProtocol` uses 14/16 B; absorbed by the 16 B slack and the 469 cap (no overflow found).

## 7. Reassembly

- **CONFIRMED** Keyed by fragmentID hex; limits: ≤256 fragments per ID, ≤1 MiB per set, ≤64 active sets, ≤4 MiB global; metadata mismatch drops the set; timeout 30 s from first fragment, swept every 10 s (`AppConstants.Fragmentation`, `FragmentManager.kt:170-330`).
- **CONFIRMED** The reassembled packet is returned with **TTL forced to 0** (`:274`) and re-enters `PacketProcessor.handleReceivedPacket`; it is therefore processed locally but never relayed as a whole. Individual fragments are relayed by the normal relay path.
- **CONFIRMED** Fragments bypass signature checks at relay; the reassembled inner packet is validated by `SecurityManager.validatePacket` as usual.
- INFERRED: 30 s window ≈ 256 fragments at ≥8.5 frag/s end-to-end; multi-hop large transfers on congested links may time out. No resume/selective-retransmit exists.

## 8. Routing

- **CONFIRMED** Default: controlled flooding. A relay forwards every valid packet not addressed to itself (`PacketProcessor.kt` ends with `packetRelayManager.handlePacketRelay(routed)` for every valid packet; `PacketRelayManager.isPacketAddressedToMe`). Encrypted DMs are therefore flooded network-wide unless a source route is used.
- **CONFIRMED** Source routing (v2): originator computes Dijkstra (unit weights) on **confirmed** (bidirectionally announced) edges from `services/meshgraph/MeshGraphService` and sets `route = intermediates` when path length ≥ 3 (`MeshCore.kt:1044-1068`, `services/meshgraph/RoutePlanner.kt`). Topology comes from ANNOUNCE TLV `0x04` with ≤10 neighbor IDs (`services/meshgraph/GossipTLV.kt`).
- **CONFIRMED** Relay with route: drop if duplicate hops; if self in route, unicast to next hop (or final recipient if last); on failure fall back to flooding. If self **not** in the route, the packet is handled by the normal flood rules (`PacketRelayManager.kt:76-115`). INFERRED: source routing reduces airtime only along on-route nodes; off-route neighbors that hear the packet still flood it.
- Doc drift CONFIRMED: `docs/SOURCE_ROUTING.md` describes TLV 0x04 as `[Count 1B][IDs]`; code and `docs/ANNOUNCEMENT_GOSSIP.md` use no count byte (`GossipTLV.kt`).

## 9. TTL

- **CONFIRMED** `MESSAGE_TTL_HOPS = 7` for user/control packets; `SYNC_TTL_HOPS = 0` for REQUEST_SYNC and sync responses (`AppConstants.kt:10-11`).
- **CONFIRMED** Relay: drop if TTL==0, else forward with TTL-1. VOICE_FRAME TTL is clamped to ≤5 when network size > 6 and is jittered 8–26 ms (`PacketRelayManager.kt:59-74`).
- **CONFIRMED** TTL is excluded from the signature (fixed 0 in preimage).
- **CONFIRMED** Bridge crossing also decrements TTL (`TransportBridgeService.prepareForwardedPacket`).
- **CONFIRMED** "TTL == 7" is used as a direct-neighbor heuristic (fresh ANNOUNCE exception `SecurityManager.kt:84-86`; `DirectLinkAnnouncementPolicy`; Noise handshake direct ingress).

## 10. Deduplication

Four independent dedup layers, all in memory:
1. **CONFIRMED** `SecurityManager.processedMessages`: key `"$peerID-${PacketIdUtil.computeIdHex(packet)}"`, ID = SHA-256(type‖senderID‖timestamp‖payload)[0..16]. Recorded **only after signature validation**; expiry 5 min; size cap 10,000; key-exchange dedup 1,000 / 60 s (`SecurityManager.kt:51-108, :389-...`, `AppConstants.Security`). Exception: re-accept ANNOUNCE with TTL ≥ 7 (new direct link).
2. **CONFIRMED** `TransportBridgeService.seenPackets`: 4,096 LRU / 5 min, per bridge kind.
3. **CONFIRMED** `GossipSyncManager` stores (for sync, not filtering).
4. **CONFIRMED** App level `services/SeenMessageStore` (`SEEN_MESSAGE_MAX_IDS=10,000`) and `IncomingMessageAdmission` (not analyzed in depth).
- INFERRED: only LEAVE (±5 min) and ANNOUNCE (±10 min, `MessageHandler.kt:31`) have timestamp freshness checks at the mesh layer. A signed broadcast MESSAGE re-injected after the 5-minute window (or after process restart) passes `SecurityManager`; suppression then depends on app-level SeenMessageStore. Not tested here.

## 11. Relay

- **CONFIRMED** `PacketRelayManager.shouldRelayPacket` (`:142-171`): always relay if TTL ≥ 4; else always if network size ≤ 3 (and ≤10 ⇒ p=1.0); 11–30 ⇒ 0.85; 31–50 ⇒ 0.7; 51–100 ⇒ 0.55; >100 ⇒ 0.4. With default TTL 7 the first three relay hops are unconditional.
- **CONFIRMED** "Network size" = `PeerManager.getActivePeerCount()` (all peers seen within the 180 s stale window, direct or relayed).
- **CONFIRMED** Relay can be disabled globally via `DebugSettingsManager.packetRelayEnabled` (`:24-26`).
- **CONFIRMED** Packets that fail validation (bad/missing signature, unknown signer, duplicate) are **not relayed**. INFERRED: a MESSAGE from a peer whose ANNOUNCE has not yet reached a relay (no signing key) is dropped at that relay rather than forwarded.
- **CONFIRMED** Relays exclude the ingress link (`relayAddress`) when re-broadcasting on BLE.

## 12. Store-and-forward

- **CONFIRMED** `mesh/StoreForwardManager.kt` (memory only): regular cache 100 msgs / 12 h, favorites 1,000 per peer, cleanup 10 min, flush with 10 ms spacing on `sendCachedMessages(peerID)` (called on key-exchange completion and peer appearance: `MeshCore.kt:249, :512`, `BluetoothMeshService.kt:300, :640`).
- **CONFIRMED** `cacheMessage()` is **never called** in `app/src/main`; it also skips `NOISE_ENCRYPTED/NOISE_HANDSHAKE/ANNOUNCE/LEAVE`, and derives recipient via `String(recipientID)` instead of hex (`:54-99`). INFERRED: relays never hold messages for absent peers; store-and-forward in the "carry for others" sense does not exist.
- **CONFIRMED** What does exist: (a) sender-side in-memory outbox in `MessageRouter` (≤100 per conversation, 24 h TTL, 2 s tick, handshake backoff 5/15/30/60 s, flush on peer appearance / session established / favorites change) (`services/MessageRouter.kt`, `AppConstants.Router`); (b) gossip sync of **public** messages to neighbors (§15); (c) Nostr relay path for mutual favorites.
- **CONFIRMED** Outbox is not persisted and is cleared by `clearAll()`; the scheduler stops with the foreground service (`MessageRouter.stopOutboxScheduler`).

## 13. Acknowledgements

- **CONFIRMED** Delivery ACK: receiver sends Noise `DELIVERED` with the messageID on successful private-message decrypt (`MessageHandler.kt:100,122,150,237-269`), TTL 7, flooded, **single attempt**.
- **CONFIRMED** Read receipt: Noise `READ_RECEIPT`; sent via `RetryingControlPacketSender` (3 attempts, 750 ms apart, coalesced per key) and marked sent in `SeenMessageStore` only if at least one transport accepted the write (`MeshCore.kt:741-782`, `mesh/RetryingControlPacketSender.kt`).
- **CONFIRMED** Over Nostr, ACKs are routed by `MessageRouter.sendDeliveryAck` only when mesh path/session is unavailable.
- **CONFIRMED** No sender-side "awaiting ACK" state machine: once `mesh.sendPrivateMessage` is called the outbox entry is removed (`MessageRouter.kt:212-214`). `MeshCore.sendPrivateMessage` with no session starts a handshake and **drops** the message (`MeshCore.kt:706-739`); the router normally guards this with `isReady()`. INFERRED: a race between `isReady` and session teardown or `UnifiedMeshService` picking a different transport can silently lose a message.
- **CONFIRMED** `RetryingControlPacketSender` notes that GATT acceptance does not prove remote processing (class KDoc).

## 14. Retry

| What | Mechanism | Evidence |
|---|---|---|
| Queued private msg (no session/route) | Outbox tick 2 s, 24 h expiry, 100/convo | `MessageRouter.kt:271-341` |
| Noise handshake for queued msgs | Backoff 5/15/30/60 s, reset on peer reappear | `MessageRouter.kt:256-268, :417-427` |
| Read receipts | 3 attempts / 750 ms | `RetryingControlPacketSender.kt` |
| GATT op failure | ≤3 callback retries per queued write | `BluetoothPacketBroadcaster.kt:615-645` |
| BLE connect | 5 s delay, 3 attempts | `AppConstants.Mesh` |
| BLE scan failure | error-specific backoff | `BluetoothGattClientManager.kt` scan callback |
| Wi-Fi socket | 3 attempts, 750 ms, then role reversal | `WifiAwareMeshService.kt:66-79` |
| Delivered msg w/o ACK | **none** | — |
| Fragment loss | **none** (30 s timeout, whole packet lost) | `FragmentManager.kt` |

## 15. Sync / gossip (`sync/`)

- **CONFIRMED** `GossipSyncManager`: stores broadcast `MESSAGE`s (LRU up to `seenCapacity`) and latest `ANNOUNCE` per sender; every **30 s** broadcasts REQUEST_SYNC (TTL 0) carrying a GCS filter of what it has; on receipt, sends missing packets back with TTL 0 (`GossipSyncManager.kt:52-233`). Initial per-peer sync **1 s** after a direct ANNOUNCE (`BluetoothMeshService.kt:595`, `WifiAwareMeshService.kt:175, :1269`).
- **CONFIRMED** Effective defaults on BLE: `seenCapacity=500`, `gcsMaxBytes=400`, FPR 1% (`BluetoothMeshService.kt:160-170`), receiver cap 1,024 B (`SyncDefaults.MAX_ACCEPT_FILTER_BYTES`). Shared instance between BLE and Wi-Fi via `service/MeshServiceHolder`.
- **CONFIRMED** Stale ANNOUNCE (> `STALE_PEER_TIMEOUT_MS`=180 s) ignored; stale peers' announcements **and their messages** pruned every 60 s.
- **CONFIRMED** FILE_TRANSFER, private/Noise traffic and VOICE_FRAME are not synced. The BLE `handleFragment` hook calls `onPublicPacketSeen` for FRAGMENT packets, which the manager ignores (type filter at `:105-110`) — dead code.
- Doc drift CONFIRMED vs `docs/sync.md`: doc says retention 100, filter 256 B, initial sync after 5 s, announcement age-out 60 s; code uses 500, 400 B, 1 s, 180 s.

## 16. Lifecycle / background

- **CONFIRMED** `service/MeshForegroundService`: `START_STICKY`; FGS types `connectedDevice|dataSync|location` in manifest; on API 34+ starts as `CONNECTED_DEVICE` (+`LOCATION` if permitted, falls back on SecurityException) (`MeshForegroundService.kt:216, :346-366`, `AndroidManifest.xml:134-138`). Started only if `MeshServicePreferences.isBackgroundEnabled(true)` and permissions are present; otherwise no service on API ≥ 26.
- **CONFIRMED** `BootCompletedReceiver` starts the FGS if `auto_start_on_boot` (default **true**).
- **CONFIRMED** Notification actions STOP / QUIT; QUIT goes through `AppShutdownCoordinator`. No `onTaskRemoved` override (grep).
- **CONFIRMED** `MessageRouter` outbox scheduler follows the FGS lifecycle (stopped with it, restarted by `getInstance`).
- INFERRED: if the user disables background mode or Android kills the process, all in-memory queues, dedup state, fragments and gossip stores are lost; on restart, peers re-announce and gossip resyncs only public messages.
- UNKNOWN: behavior under OEM battery killers / Doze on real devices — requires physical-device runs (`docs/device-transport-test-matrix.md` is an unchecked checklist).

## 17. Battery

- **CONFIRMED** Modes: `PERFORMANCE, BALANCED, POWER_SAVER, ULTRA_LOW_POWER`; battery bands NORMAL/LOW(≤20%)/CRITICAL(≤10%) (`PowerManager.kt:288-312`).
  - Background: POWER_SAVER (ULTRA_LOW if critical). Foreground: PERFORMANCE if charging, else by band.
- **CONFIRMED** BLE scan duty cycle (`PowerManager.kt:315-336`, applied by `BluetoothGattClientManager.applyPowerProfile` `:623-649`):
  - background + direct peers: 1 s on / 29 s off; background, no peers: 1 s / 59 s
  - PERFORMANCE: continuous; BALANCED: 8 s / 2 s; POWER_SAVER: 2 s / 28 s; ULTRA_LOW: 1 s / 29 s
- **CONFIRMED** Advertising: never stopped; mode/TX power scale LOW_LATENCY/HIGH → LOW_POWER/ULTRA_LOW. Announce interval 30 s fg; 60–300 s bg; skipped when no direct peers.
- **CONFIRMED** GATT connections are **not** dropped in low-power modes (max stays 8). Gossip sync stays at a fixed 30 s regardless of mode (`GossipSyncManager.kt:57`).
- **CONFIRMED** `AppConstants.Power.SCAN_*` and `MAX_CONNECTIONS_*` constants are unused (resolver hard-codes values).
- **CONFIRMED** No user-facing PAUSED mode; the only "pause" is stopping the FGS / disabling BLE in debug settings.

## 18. Test coverage (JVM unit tests only; no `androidTest` dir exists)

| Area | Tests (file: @Test count) |
|---|---|
| Wire format | `protocol/BinaryProtocolTest` 54, `contracts/ClientRewriteWireContractTest` 12, `protocol/DecompressionResourcePoolTest` 4, `MeshPacketUtilsTest` 4 |
| Fragmentation | `mesh/FragmentManagerTest` 7, `mesh/FragmentingPacketSenderTest` 3, `FileTransferTest` 13 |
| Padding | `mesh/BLEPacketPaddingPolicyTest` 3 |
| Security/dedup | `mesh/SecurityManagerTest` 27 |
| Relay/routing | `mesh/PacketRelayManagerTest` 4, `services/meshgraph/MeshGraphServiceTest` 6 |
| Handlers | `mesh/MessageHandlerTest` 19, `mesh/PacketProcessorAnnounceSideEffectTest` 4, `mesh/DirectLinkAnnouncementPolicyTest` 3 |
| Peers | `PeerManagerTest` 15, `mesh/AuthenticatedPeerStateCoordinatorTest` 7 |
| BLE | `mesh/BluetoothConnectionManagerTest` 1, `mesh/BluetoothConnectionTrackerLinkObservationTest` 2 |
| Wi-Fi Aware | `wifi-aware/SyncedSocketContractTest` 6, `IngressLinkPolicyTest` 2, `WifiAwareConnectionTrackerTest` 2, `wifiaware/WifiAwareHotspotHoldTest` 3 |
| Bridge | `service/TransportBridgeServiceTest` 2 |
| Retry/outbox | `mesh/RetryingControlPacketSenderTest` 4, `services/MessageRouterTest` 7 |
| Sync | `sync/GCSFilterTest` 3 |
| Power | `mesh/PowerProfileResolverTest` 7 |
| Private media | `mesh/PrivateMediaSecurityTest` 6, `mesh/PrivateMediaTransferPreparerTest` 11 |

**No tests** for: `StoreForwardManager`, `GossipSyncManager` (only GCS codec), `MeshCore`, `UnifiedMeshService`, `BluetoothMeshService`, `BluetoothPacketBroadcaster`, GATT client/server managers, `WifiAwareMeshService` main logic, `MeshForegroundService`/boot. `docs/client-rewrite-contracts.md` references `ClientRewritePrimitiveContractTest`, `ClientRewriteNostrContractTest`, `RewriteContractTest` which **do not exist** in `app/src/test`.

## 19. Mismatches vs `docs/product/04_MESH_ARCHITECTURE.md`

| Product assumption | Reality | Label |
|---|---|---|
| Flow UI→ViewModel→Domain→Message Router→Transport Manager | No domain layer; `ChatViewModel`→`MessageRouter` (private only) / `UnifiedMeshService` (public) → per-transport services; Router doesn't see public msgs | CONFIRMED |
| Message Router owns dedup, TTL, relay, retry, acks | These live in `SecurityManager`, `PacketRelayManager`, `FragmentManager`, `MessageHandler`, duplicated per transport; Router only chooses mesh/Nostr/queue | CONFIRMED |
| `MeshTransport` with start/stop/discover/connect/disconnect/send(ByteArray) | Existing `MeshTransport` is packet-level (`RoutedPacket`/`BitchatPacket`), no lifecycle; BLE doesn't implement it | CONFIRMED |
| Transport Manager selects transports | Split between `UnifiedMeshService` (per-call choice) and `TransportBridgeService` (relay bridge); no single manager | CONFIRMED |
| Persistence is source of truth incl. outbox, relay state, delivery state | Only conversation history in SQLite; outbox, relay/dedup/fragment/gossip/store-forward all in memory | CONFIRMED (delivery-state persistence in history DB: UNKNOWN, not analyzed) |
| Lifecycle: persist → enqueue → … → ack → update delivery | Enqueue only when not sendable; no ack-wait / resend; message lost on process death | CONFIRMED |
| Store-and-forward | Only sender outbox + public gossip; relay-side S&F is dead code | CONFIRMED |
| Failure model incl. process restart, fragmented message failure | No persistence → restart loses queue; fragment loss = whole packet lost, no retransmit | CONFIRMED |
| Battery modes ACTIVE/BALANCED/LOW_POWER/PAUSED | PERFORMANCE/BALANCED/POWER_SAVER/ULTRA_LOW_POWER; no PAUSED; automatic, not user-selected | CONFIRMED |
| "Never run uncontrolled scanning/advertising/retry loops" | Scanning duty-cycled; advertising always on; gossip fixed 30 s; outbox 2 s tick while non-empty | CONFIRMED (bounded) |
| Delivery ack | Exists, single-shot, receiver-driven; sender doesn't retry | CONFIRMED |

## 20. Fragile areas & mesh technical debt

### CONFIRMED
1. **Coordinator duplication**: `BluetoothMeshService` (1,660 l) re-implements `MeshCore` (1,092 l); fixes must be applied twice (e.g., both have `applyRouteIfAvailable`, `signPacketBeforeBroadcast`, delegate wiring). `mesh/BluetoothMeshService.kt:250-1621`, `mesh/MeshCore.kt`.
2. **Dead store-and-forward**: `StoreForwardManager.cacheMessage` unused; recipient decoding bug (`String(recipientID)` vs hex) (`StoreForwardManager.kt:54-99`).
3. **No durable outbox / no ACK-driven retry**: `MessageRouter.kt:84, :212-214`.
4. **Negotiated MTU ignored**; fixed 512 B frames (`BluetoothGattClientManager.kt:501-520`, `AppConstants.Fragmentation`).
5. **Production behavior wired to debug settings** (BLE enable, relay enable, conn limits, gossip capacity/GCS, Wi-Fi Aware enable): `PacketRelayManager.kt:24-26`, `BluetoothConnectionManager.kt:160-216`, `BluetoothMeshService.kt:160-170, :220-227`.
6. **`runBlocking` inside packet actors** for Noise handshake/decrypt (`BluetoothMeshService.kt:571, :575`).
7. **Unsynchronized, never-pruned actor map** in `PacketProcessor` (`actors = mutableMapOf`, `:55`), `processPacket` called from GATT callback threads; one actor per sender peerID forever.
8. **Reference comparison on ByteArray**: `packet.recipientID != SpecialRecipients.BROADCAST` (`BluetoothPacketBroadcaster.kt:397`) is always true (harmless today, misleading).
9. **Stable peerID broadcast in BLE scan response and Wi-Fi Aware service info** (`BluetoothGattServerManager.kt:396-405`, `WifiAwareMeshService.kt:372, :442`).
10. **Hard-coded Wi-Fi Aware PSK** `"bitchat_secret"` (`WifiAwareMeshService.kt:70`).
11. **Nordic BLE dependency unused** (`app/build.gradle.kts:167`, no imports).
12. **Doc drift**: `docs/sync.md` constants; `docs/SOURCE_ROUTING.md` TLV count byte; `docs/client-rewrite-contracts.md` missing tests.
13. **Dead gossip hook** for FRAGMENT (`BluetoothMeshService.kt:624-633` vs `GossipSyncManager.kt:105-110`); unused `AppConstants.Power` constants.
14. **Fragment-count ceiling vs UI file limit** (256 fragments ≈ 115 KB vs ~9.87 MB UI limit).

### SUSPECTED (needs test/device evidence)
- Bridged own-broadcasts arriving with TTL 6 confuse "direct ingress" heuristics (§2.3).
- Replay of old signed broadcast MESSAGE after 5-min dedup expiry/restart (§10).
- Two Noise sessions per peer (one per transport) causing duplicate handshakes and ambiguous "ready" state (§2.1).
- 30 s reassembly timeout vs slow multi-hop large transfers (§7).
- Message loss in `MeshCore/BluetoothMeshService.sendPrivateMessage` when session drops between router check and send (§13).
- Flooded DMs + unconditional relay for TTL≥4 ⇒ broadcast storms in dense events (>30 nodes); relay probability only applies at TTL ≤3.

## 21. Open questions
1. Do iOS peers negotiate MTU <517 in practice, and does Android truncate notifications? (device test)
2. Is relay-side store-and-forward intentionally dropped upstream, or a regression? (check upstream history)
3. Should Wi-Fi Aware bypass 512-byte fragmentation for bulk (capability bit 1 "Wi-Fi bulk" exists in `ANNOUNCEMENT_GOSSIP.md`)?
4. Is the stable peerID in advertisements an accepted upstream trade-off? Any ephemeral-ID mode?
5. How does `ConversationRepository` represent delivery state and is it updated from `didReceiveDeliveryAck`?
6. What is the measured relay load in a 20–50 device event scenario?
7. Should the outbox be persisted (SQLite, encrypted with `ConversationStorageCipher`)?
8. Upstream license/compat constraints on changing relay probability or adding ACK-retries (wire unaffected, behavior changes).

## 22. Sources
- Repo paths cited inline (all repo-relative).
- Existing specs: `docs/SOURCE_ROUTING.md`, `docs/ANNOUNCEMENT_GOSSIP.md`, `docs/sync.md`, `docs/file_transfer.md`, `docs/NOISE_PEER_ID_BINDING.md`, `docs/device-transport-test-matrix.md`, `docs/client-rewrite-contracts.md`, `docs/product/04_MESH_ARCHITECTURE.md`.
- External: Android Developers, "Wi-Fi Aware overview" — https://developer.android.com/develop/connectivity/wifi/wifi-aware (coexistence with Wi-Fi Direct/SoftAP, location/Wi-Fi-off unavailability, ~255-byte discovery messages, `getAvailableAwareResources()`).
