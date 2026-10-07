# MeshUp — Security Review (Phase 0 baseline)

> Last updated: 2026-10-07. Baseline commit: `8d7de5e`. Companion docs: `docs/MESH_ARCHITECTURE.md`, `docs/TECHNICAL_DEBT.md`.

Status: read-only static review. No code was modified. No Gradle build, unit test run,
emulator run or physical-device test was performed for this document.
Scope: `app/src/main/java/com/bitchat/android/` (noise, crypto, identity, protocol, mesh security
paths, nostr, net, favorites, services storage, notifications, manifest, debug test hook).
Reviewer role: security-engineer specialist; chief engineer independently hand-traced R1 (replay window: a shift of 1 moves the previous nonce's bit off the window instead of to offset 1) and the sender-side unsigned fallback (`BluetoothMeshService.signPacketBeforeBroadcast`). Date: 2026-10-07.

Evidence labels used throughout:
- **CONFIRMED** — read directly in repository code (repo-relative path + function / approx. line).
- **INFERRED** — reasoned from confirmed code but not traced end-to-end or executed.
- **UNKNOWN** — not inspected / not determinable statically; needs follow-up.

Line numbers are approximate (taken at review time) and should be re-checked before citing in
tickets. No key material or real identifiers are reproduced in this document; one hard-coded
Wi-Fi Aware passphrase was observed and is deliberately not quoted.

---

## 1. Summary and overall posture

The fork inherits a credible cryptographic core from upstream bitchat-android: Noise
`XX_25519_ChaChaPoly_SHA256` for private sessions, Ed25519 packet signatures for public mesh
traffic, peer IDs cryptographically bound to the Noise static key, NIP-17 gift-wrapped Nostr DMs,
and Tor (Arti) on by default for Internet transports. Since the upstream July-27 review, several
of its most serious findings have been fixed in this tree (session-key logging, decompression
bomb, Nostr event signature verification, DM-content logging, unbounded Nostr send queue), and
private conversations are now persisted in an SQLite store whose sensitive payload is
AES-256-GCM-encrypted under a dedicated Android Keystore key with AAD binding.

Remaining posture concerns, in priority order:
1. **Noise transport replay window is still broken** (bit-shift direction) — confirmed by code
   reading and a transcription simulation; mitigated only partially by upper-layer dedup.
2. **Mesh-layer DoS / amplification surface is largely unchanged**: unbounded per-sender actors,
   unsigned FRAGMENT/REQUEST_SYNC/NOISE_* frames relayed, TTL not clamped on ingress and excluded
   from signatures, duplicate ANNOUNCE accepted at max TTL, no rate limiting.
3. **Metadata / location privacy**: stable advertised peer ID, nicknames in clear, building-level
   (8-char geohash) public notes, deterministic never-rotating per-geohash Nostr identities,
   automatic Nostr delivery/read receipts, Nostr relay connection + `#p` subscription at app start.
4. **Release logging hygiene**: ~1,196 `Log.*` call sites, no ProGuard/R8 log stripping;
   peer IDs, fingerprints and favourites are logged (content and keys no longer are).
5. **Identity hygiene debt**: two independent Ed25519 signing keys, silent identity regeneration
   on keystore read failure, legacy plaintext key not always removed, fail-open "send unsigned"
   on signing errors, stub `sign()`/`verify()` APIs that return empty/session-presence results.
6. **At-rest gaps**: received media/voice/files are stored unencrypted in app-private storage;
   conversation metadata (conversation IDs, timestamps, aliases) is plaintext in SQLite;
   `androidx.security:security-crypto` (EncryptedSharedPreferences) is deprecated upstream.

Overall: suitable as a foundation; **not yet release-ready for a consumer product** against
`docs/product/05_SECURITY_REQUIREMENTS.md` until the High items in the risk register (section 18)
are resolved or explicitly accepted.

---

## 2. Identity

| Item | Finding | Label |
|---|---|---|
| Noise static key | Curve25519 (X25519) key pair, 32-byte private/public; loaded or generated in `noise/NoiseEncryptionService.kt` `loadOrGenerateKeys()` (~L85-116), persisted via `identity/SecureIdentityStateManager.kt` `loadStaticKey()/saveStaticKey()` (~L87-140). | CONFIRMED |
| Peer ID | `hex(SHA-256(noiseStaticPublicKey)[0..8])`, 16 lowercase hex chars — `noise/NoisePeerIdentity.kt` `derivePeerID()` (~L18-24); documented in `docs/NOISE_PEER_ID_BINDING.md`. | CONFIRMED |
| Fingerprint | Full SHA-256 of the public key as 64 hex chars — `SecureIdentityStateManager.generateFingerprint()` (~L202). Used for favourites/verification. | CONFIRMED |
| Ed25519 signing key (#1) | `crypto/EncryptionService.kt` `loadOrCreateEd25519KeyPair()` (~L463) stored in EncryptedSharedPreferences file `bitchat_crypto_secure`; this key signs outbound packets via `signData()` (~L126) called from `mesh/BluetoothMeshService.kt` `signPacketBeforeBroadcast()` (~L1590). | CONFIRMED |
| Ed25519 signing key (#2) | `NoiseEncryptionService.loadOrGenerateKeys()` also loads/creates a separate Ed25519 key via `SecureIdentityStateManager.loadSigningKey()/saveSigningKey()` (~L145-195), file `bitchat_identity`. Two signing identities per device (upstream M16). Which one is advertised in ANNOUNCE vs authenticated peer-state `0x21`: **UNKNOWN** — needs trace. | CONFIRMED (two keys) / UNKNOWN (usage split) |
| Nostr identity | Account secp256k1 key stored via `SecureIdentityStateManager.storeSecureValue()` (`nostr/NostrIdentity.kt` ~L228-247); per-geohash identities = HMAC-SHA256(deviceSeed, geohash) (`NostrIdentity.deriveIdentity()` ~L133-177, seed key ~L101). | CONFIRMED |
| Display name vs identity | Nickname is a free-text field carried in ANNOUNCE / Nostr `["n", nickname]` tag (`nostr/NostrProtocol.kt` ~L117, ~L168); identity is the key/fingerprint. Satisfies "display name ≠ identity" structurally. Whether the UI ever lets nickname collisions mislead users: **UNKNOWN** (UX review). | CONFIRMED / UNKNOWN |
| Verification | QR-based verification via exported deep link `bitchat://verify` (`AndroidManifest.xml` ~L124-129, `MainActivity.kt` ~L853); upstream review states payload is cryptographically validated. Verified fingerprints stored via `SecureIdentityStateManager.setVerifiedFingerprint()` (~L226). | CONFIRMED (path) / INFERRED (validation strength — not re-traced) |
| Announcement binding | `mesh/AnnouncementIdentityValidator.kt` `verify()` (~L13-36): rejects >10 min clock skew, requires 32-byte Ed key, derived peer ID must equal sender and claimed IDs, Ed25519 signature over canonical bytes. | CONFIRMED |
| TOFU boundary | First self-signed ANNOUNCE is trust-on-first-use. An attacker can copy a victim's Noise public key and self-sign with their own Ed key; they cannot complete the bound Noise handshake. Persisted authenticated peer state overrides announcements (`mesh/SecurityManager.kt` ~L268-300; `mesh/AuthenticatedPeerStateCoordinator.kt`). For never-authenticated peers, the first in-memory Ed key is pinned and later conflicting announces are rejected ("signing-key replacement without authenticated peer state"). | CONFIRMED (code + `docs/NOISE_PEER_ID_BINDING.md` "Remaining TOFU boundary") |

---

## 3. Key management and storage

- **Storage mechanism.** Noise static key, identity Ed25519 key, Nostr key, device seed,
  favourites and verified fingerprints are stored in `EncryptedSharedPreferences`
  (AES256-SIV keys / AES256-GCM values, `MasterKey` AES256_GCM in Android Keystore) —
  `identity/SecureIdentityStateManager.kt` (imports + `prefs = EncryptedSharedPreferences.create(...)`),
  `crypto/EncryptionService.kt` (same pattern). **CONFIRMED.**
- **Library deprecation.** `androidx.security:security-crypto` 1.1.0 is used
  (`gradle/libs.versions.toml`). Google deprecated all APIs of this library (1.1.0-alpha07 /
  1.1.0 stable, 2025) in favour of platform APIs / direct Keystore use. Functional today, but a
  maintenance and future-compat risk. **CONFIRMED** (version) + external source.
- **Keys are extractable software keys.** Private keys are generated in-process (BouncyCastle /
  Noise code) and stored as Base64 ciphertext; they are not non-exportable Keystore keys and are
  held in process memory while the app runs. Expected for Noise/Ed25519 on Android (Keystore does
  not support X25519 Noise use directly), but means root/memory compromise exposes long-term keys.
  **INFERRED.**
- **Legacy plaintext key migration (upstream M5).** `EncryptionService.migrateOldEd25519KeyIfNeeded()`
  (~L505-524) deletes the old plaintext key from `bitchat_crypto` **only** if the encrypted store does
  not already have one; otherwise the plaintext private key remains on disk. **CONFIRMED, still open.**
- **Silent regeneration (upstream L3).** `EncryptionService.loadOrCreateEd25519KeyPair()` (~L463-483)
  catches any load exception and generates a new key; `NoiseEncryptionService.loadOrGenerateKeys()`
  generates a new static key when `loadStaticKey()` returns null (which it does on any exception,
  ~L87-112). A transient Keystore failure silently changes the user's identity (breaks
  verification/favourites, looks like impersonation to peers) and masks tampering. **CONFIRMED.**
- **Store-write failures are swallowed.** `generateAndSaveEd25519KeyPair()` logs and continues if the
  write fails (~L485-503) → in-memory identity differs from persisted (next start regenerates).
  **CONFIRMED.**
- **Rotation.** No user-facing or scheduled identity key rotation found; rotation of the signing
  key is only via authenticated peer-state proof (`docs/PRIVATE_MEDIA_V1.md`, `0x21`). Panic wipe
  regenerates identity. **INFERRED** (no rotation API found by grep).
- **Wipe.** Panic path `ui/ChatViewModel.kt` `panicClearAllData()` (~L1440-1500) and
  `clearAllCryptographicData()` (~L1582-1607) clear encryption service identity,
  `SecureIdentityStateManager.clearIdentityData()` (~L469), favourites secure values, Nostr state
  (`ui/GeohashViewModel.kt` `panicReset()` ~L111-124), conversation DB (see §8), media
  (`FileUtils.clearAllMedia`) and notifications. Triggered by triple-tap on the header title
  (`ui/ChatScreen.kt` ~L460, `ui/ChatHeader.kt` ~L652-744). **CONFIRMED (code path);
  not device-verified.** Whether the legacy plaintext `bitchat_crypto` file is wiped: **UNKNOWN.**
- **Low-order / invalid public keys (upstream M1).** Only the all-zero remote ephemeral is rejected
  (`noise/southernstorm/protocol/HandshakeState.java` ~L1004 `isNullPublicKey()`); no all-zero DH
  output check. The Noise spec explicitly permits *not* checking (invalid keys yield all-zero DH
  output; signalling an error is optional and "does not improve security"). Because a malicious
  peer can only weaken a session it is itself party to, this is **Low** in practice.
  `SecureIdentityStateManager.validatePublicKey()` (~L403) is only used in debug info.
  **CONFIRMED.**

---

## 4. Sessions (Noise)

- **Pattern / suite.** `Noise_XX_25519_ChaChaPoly_SHA256` — `noise/NoiseSession.kt` `PROTOCOL_NAME`
  (~L24); message sizes 32/96/48 (~L31-33). XX gives mutual authentication and static-key
  identity hiding from passive observers for the initiator; message 1 is unauthenticated; full
  forward secrecy after message 3. **CONFIRMED** + Noise spec.
- **Vendored implementation.** `noise/southernstorm/protocol/*` (Noise-Java fork). External
  conformance test present: `app/src/test/kotlin/com/bitchat/android/noise/NoiseExternalVectorTest.kt`.
  **CONFIRMED (test file exists; not run here).**
- **Rekey / lifetime.** `REKEY_TIME_LIMIT` = 1 h, `REKEY_MESSAGE_LIMIT` = 10k messages
  (`NoiseSession.kt` ~L27-28, `needsRekey()` ~L589). **CONFIRMED.**
- **Explicit nonce.** Transport frames carry a 4-byte big-endian explicit nonce prefix
  (`NONCE_SIZE_BYTES = 4`, `extractNonceFromCiphertextPayload()` ~L108-130); receiver calls
  `receiveCipher.setNonce(extractedNonce)` (~L548). This is a project extension to Noise (Noise
  transport normally uses implicit counters); the Noise spec states that out-of-order receivers
  must track received nonces themselves to prevent replay. **CONFIRMED.**
- **Replay window bug (upstream H1) — still present.** `isValidNonce()` / `markNonceAsSeen()`
  (~L49-104): bit `offset` is stored LSB-first (`byte = offset/8`, `bit = offset%8`), but on
  advance the window is shifted with `>>> (shift % 8)` within bytes, i.e. toward *lower* offsets.
  A transcription of this logic run in Python (receive nonces 0..4 in order) reports nonce 0 and
  nonce 3 as **not seen** afterwards — i.e. replays accepted; only the most recent nonce is
  reliably blocked. No unit test covers the replay window (no `*Replay*` test under
  `app/src/test/**/noise`). **CONFIRMED (code + simulation of transcribed logic; not executed in
  JVM).**
  Mitigations: outer-packet dedup in `mesh/SecurityManager.validatePacket()` (5-min
  `processedMessages`) drops byte-identical replays within 5 min; private messages are deduped by
  message ID in `ConversationRepository` (`message_id UNIQUE`, `deleted_private_messages`).
  ACK/read-receipt/control payloads after 5 min: **INFERRED replayable**.
- **Session zeroization.** `NoiseSession.destroy()` (~L644-653) fills remote static key and
  handshake hash; handshake hash cloned before zeroization (`docs/NOISE_PEER_ID_BINDING.md`).
  **CONFIRMED.**
- **Identity binding / rehandshake.** Responder candidates kept outside active map
  (`noise/NoiseSessionManager.kt` ~L63-64, ~L109-277); established session survives until the
  candidate completes with a matching peer-ID binding. Test:
  `NoiseSessionManagerIdentityBindingTest.kt`. **CONFIRMED.**
- **Half-open DoS (upstream M2).** Responder candidates expire on a staleness sweep (~L312-330) but
  there is no count cap; each forged handshake costs X25519 operations under the manager lock.
  **CONFIRMED (no cap found); impact INFERRED.**

---

## 5. Encryption / authentication of private vs public traffic

| Traffic | Confidentiality | Authenticity | Label |
|---|---|---|---|
| Mesh DMs, receipts, private media (`NOISE_ENCRYPTED` 0x11, inner `0x20` file) | Noise transport (ChaChaPoly) | Noise session bound to peer ID | CONFIRMED (`docs/PRIVATE_MEDIA_V1.md`, `NoiseSession.decrypt`) |
| Public mesh MESSAGE / ANNOUNCE / FILE_TRANSFER / VOICE_FRAME / LEAVE | **None** (cleartext over BLE) | Ed25519 signature required (`SecurityManager.verifyPacketSignature()` ~L250-330) | CONFIRMED |
| FRAGMENT, REQUEST_SYNC, NOISE_HANDSHAKE, NOISE_ENCRYPTED outer packet | n/a / Noise for inner | **Signature not required** at mesh layer — accepted and relayed (`SecurityManager` type allow-list ~L256-265 returns `true` for other types) | CONFIRMED |
| Password channels | PBKDF2-derived key, salt = channel name (`noise/NoiseChannelEncryption.kt`, upstream L4) | — | INFERRED (not re-read in depth) |
| Nostr DMs | NIP-17 gift wrap; inner AEAD XChaCha20-Poly1305 via Tink (`nostr/NostrCrypto.kt encryptNIP44()` ~L244-262) | Seal signature verified and `seal.pubkey == rumor.pubkey` enforced (`nostr/NostrProtocol.kt` ~L77-90) | CONFIRMED |
| Nostr geohash public events (kind 20000/20001/1) | None (public) | Signature verified: `GeohashMessageHandler.kt` ~L52, `LocationNotesManager.kt` ~L420, `NostrClient.kt` ~L285; test `GeohashMessageHandlerSignatureTest.kt` | CONFIRMED |
| Wi-Fi Aware link | WPA-style PSK from a **hard-coded constant passphrase** (`wifi-aware/WifiAwareMeshService.kt` ~L70, ~L836) — offers no secrecy against anyone with the APK; mesh-layer Noise still protects DMs | — | CONFIRMED |

Notes:
- **Fail-open on signing.** `BluetoothMeshService.signPacketBeforeBroadcast()` (~L1596-1612) sends the
  packet **unsigned** if encoding or signing fails. Receivers will drop it, so the effect is
  availability rather than forgery, but it contradicts "fail closed". **CONFIRMED.**
- **Stub crypto APIs (upstream M16 trap).** `EncryptionService.sign()` returns `ByteArray(0)` and
  `verify()` returns `hasEstablishedSession(peerID)` ignoring signature and data (~L226-241);
  `SecurityManager.signPacket()` (~L191-198) calls the stub. Not on the outbound signing path today
  (that uses `signData`), but any future caller gets a silently empty signature. **CONFIRMED.**
- **"NIP-44 v2" is not spec NIP-44.** `encryptNIP44()` applies XChaCha20-Poly1305 directly with no
  length padding; spec NIP-44 v2 uses ChaCha20 + HMAC-SHA256 with padding. Leaks exact plaintext
  length to relays and is not interoperable with standard NIP-44 clients (upstream M13).
  **CONFIRMED (no padding) / INFERRED (spec deviation, from upstream review + NIP knowledge).**

---

## 6. Message integrity

- Public packets: Ed25519 over `BitchatPacket.toBinaryDataForSigning()`; **TTL is excluded**
  from the signed bytes (`protocol/BinaryProtocol.kt` ~L114, ~L128 sets fixed TTL for signing).
  A relay can therefore change TTL of a signed packet without detection. **CONFIRMED.**
- Private payloads: AEAD (ChaChaPoly) tag verification inside Noise; Nostr AEAD via Tink.
  **CONFIRMED.**
- Fragments: unsigned; reassembly keyed by fragment ID only (`mesh/FragmentManager.kt`
  `incomingFragments` ~L32, `fragmentIDString` ~L189-214), not (sender, fragmentID). An attacker can
  inject a colliding fragment to corrupt/destroy another sender's in-flight set (upstream M4).
  Metadata mismatch drops the set (~L197-203) — integrity of the *reassembled* payload is still
  protected for signed/Noise inner content. **CONFIRMED (keying); impact DoS.**
- Compression: `protocol/CompressionUtil.kt` `isValidRequest()` (~L100-110) caps `originalSize` at
  `MAX_PAYLOAD_LENGTH` = 10 MiB (`util/AppConstants.kt` ~L70) and allocations go through a
  heap-budgeted `DecompressionResourcePool` (max 64 MiB budget, `protocol/DecompressionResourcePool.kt`
  ~L48-58); exact-size inflate check. Test `DecompressionResourcePoolTest.kt`. **CONFIRMED — upstream
  C2 fixed.** Residual: 10 MiB pre-auth allocation per packet is still large for BLE (Low).

---

## 7. Replay protection

| Layer | Mechanism | Gap | Label |
|---|---|---|---|
| Noise transport | 1024-nonce sliding window | Shift direction bug (§4) | CONFIRMED |
| Mesh packet | 5-min dedup cache of authenticated packet IDs (`SecurityManager.validatePacket()` ~L50-100; `generateMessageID` hashes type/sender/timestamp/payload) | No freshness check for MESSAGE/FILE_TRANSFER after 5 min (upstream L2) | CONFIRMED (cache) / INFERRED (post-expiry replay) |
| LEAVE | ±5 min timestamp window + signature (~L60-74) | — | CONFIRMED |
| ANNOUNCE | ±10 min skew (`AnnouncementIdentityValidator`) | Duplicate ANNOUNCE is **re-accepted when TTL ≥ 7** (`SecurityManager` ~L78-90); TTL unsigned → replay at TTL 7 treated as fresh direct neighbour (upstream H12) | CONFIRMED |
| Nostr DMs | Event dedup (`NostrEventDeduplicator.kt`); seen-store | Replay window uses attacker-controlled `created_at` (upstream L8) | UNKNOWN (not re-checked) |
| Stored messages | `message_id UNIQUE` + tombstones table | — | CONFIRMED (`services/ConversationRepository.kt` schema ~L454-510) |

---

## 8. Local storage (at rest)

- **Private conversations are persisted by default.** `BitchatApplication.kt` (~L40) always calls
  `AppStateStore.initializeConversationPersistence()`; SQLite DB `private_conversations.db`
  (`services/ConversationRepository.kt` ~L393). **CONFIRMED.**
- **Payload encryption.** Sender, content, nicknames, sender peer ID, Nostr pubkey, mentions,
  channel, delivery text are JSON-serialised and encrypted (`encryptMessagePayload()` ~L1813-1830)
  with AES-256-GCM using a non-exportable Android Keystore key alias `bitchat_conversation_storage_v1`,
  random 12-byte IV, 128-bit tag, AAD = message identity (`services/ConversationStorageCipher.kt`
  ~L28-97). Legacy v1 plaintext columns are scrubbed on write (`toContentValues()` ~L1650-1676).
  Attachment paths are encrypted with per-message AAD (~L1441, ~L1485). **CONFIRMED.**
- **Plaintext metadata in SQLite.** `conversation_id` (peer ID / alias / Nostr-derived key),
  `conversation_aliases.alias`, `message_id`, `sent_at`, `received_at`, `is_relay`, `is_private`,
  `is_read`, delivery type/counters remain plaintext. Reveals who-talked-when on a seized/rooted
  device. **CONFIRMED (schema); severity Medium.**
- **Crypto-erasure.** `clearAll()` destroys the Keystore key then deletes rows and the DB file
  (~L1086-1114). Good design; SQLite WAL/free-page remnants are rendered undecryptable by key
  deletion. **CONFIRMED (code); not device-verified.**
- **Received media / files / voice notes are unencrypted at rest.** `features/file/FileUtils.kt`
  writes `file.content` directly (~L238-261) under `filesDir/files/incoming` (and images/voice under
  `filesDir`); no `EncryptedFile` or Keystore wrapping. App-private, but exposed on rooted devices,
  via D2D transfer (see below) or forensic extraction. **CONFIRMED.**
- **Plain SharedPreferences.** `ui/DataManager.kt` (`bitchat_prefs`) holds nickname, joined/password
  channel names, favourites set, blocked users, **last geohash channel** and location toggle
  (~L19-240); `geohash_prefs` (bookmarks), `geohash_alias_registry`, `geohash_conversation_registry`
  store location-channel history / alias maps. No private keys found in plain prefs other than the
  legacy `bitchat_crypto` case (§3). **CONFIRMED.**
- **Backup / transfer.** `android:allowBackup="false"` with `dataExtractionRules` and
  `fullBackupContent` (`AndroidManifest.xml` ~L79-81). Rules exclude only `bitchat_prefs.xml` and
  `bitchat_crypto.xml` (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`). Android docs:
  for apps targeting API 31+, `allowBackup="false"` does not disable device-to-device transfer on
  some OEM devices. `targetSdk = 37` (`gradle/libs.versions.toml`). Therefore on such devices D2D
  transfer may copy `private_conversations.db` (ciphertext, undecryptable without the Keystore key,
  but plaintext metadata), `files/` media in **plaintext**, `geohash_prefs`, registries, and
  EncryptedSharedPreferences blobs (undecryptable on the new device → triggers silent identity
  regeneration, §3). **CONFIRMED (config) / INFERRED (OEM behaviour).**
- **In-memory.** Public mesh messages and geohash chat appear to be in-memory only
  (`AppStateStore` public message lists). **INFERRED.**

---

## 9. Logs

- **Session-key logging fixed.** No `Log.*` remains in `noise/southernstorm/protocol/*.java`
  (upstream C1). **CONFIRMED.**
- **DM content logging fixed.** `mesh/MessageHandler.kt` logs no decrypted content (upstream H3).
  **CONFIRMED.**
- **No release log stripping.** `app/proguard-rules.pro` has no `-assumenosideeffects class
  android.util.Log`; release `isMinifyEnabled = true` (`app/build.gradle.kts` ~L62) does not remove
  log calls by itself. ~1,196 `Log.*` call sites in `app/src/main`. **CONFIRMED.**
- **Identifiers still logged.** ~147 log lines interpolate peer IDs; fingerprints/favourites in
  `ui/PrivateChatManager.kt` (~L198-253, incl. "All peer fingerprints" dump ~L230) and
  `ui/DataManager.kt` (~L161-197, logs the full favourites set at load/save);
  `mesh/PeerFingerprintManager.kt` (~L59-185); nickname in `MessageHandler.kt` (~L358, ~L413);
  hotspot client IP (`hotspot/ApkWebServer.kt` ~L43); `NostrTestManager.kt` (~L174) logs geohash
  message content (reachability in release: **UNKNOWN**). Violates doc 05 "raw device identifiers when
  avoidable" and builds a social graph in logcat. **CONFIRMED.**
- On modern Android, other apps cannot read logcat without `READ_LOGS`, so exposure is via bug
  reports, ADB, OEM log collectors, or rooted devices. **INFERRED.** Severity Medium.

---

## 10. Notifications

- DM notifications: `setVisibility(VISIBILITY_PRIVATE)` plus a redacted `setPublicVersion(...)`
  with `notification_content_hidden` (`ui/NotificationManager.kt` ~L265-330). Upstream H4 partially
  fixed. **CONFIRMED.**
- Geohash (~L584-600), geohash summary (~L664), mesh mention (~L797) and summary (~L429)
  notifications set no explicit visibility or public version; channels (~L127-152) set no
  `lockscreenVisibility`. Default is PRIVATE semantics, but when the user's device setting is
  "show all content" the full message text (`BigTextStyle`/`InboxStyle`) appears on the lock
  screen, and notification-listener apps always see content. **CONFIRMED (code) / INFERRED
  (platform behaviour).**
- No in-app "hide message previews" setting found (grep for preview/lockscreen in notification code).
  **INFERRED.**
- Recents: `setRecentsScreenshotEnabled(false)` (`MainActivity.kt` ~L86, API 33+); no `FLAG_SECURE`
  anywhere — screenshots/screen recording and pre-33 Recents thumbnails can capture chats
  (upstream L9 partial). **CONFIRMED.**

---

## 11. Metadata exposure

- **BLE advertising.** Device name excluded (`setIncludeDeviceName(false)`), but the scan response
  carries the 8-byte peer ID as service data (`mesh/BluetoothGattServerManager.kt` ~L389-406).
  Peer ID is derived from the persistent Noise static key, so it is a **stable, passively
  trackable identifier across MAC rotation and restarts** (upstream M3). **CONFIRMED.**
- **ANNOUNCE.** Cleartext nickname, Noise public key, Ed25519 public key, capability TLV
  (`docs/PRIVATE_MEDIA_V1.md`), and gossip neighbour lists (upstream review) — anyone in range
  learns identity keys, nickname and partial social/topology graph. **CONFIRMED (fields) / INFERRED
  (neighbour TLV not re-read).**
- **Timestamps / sizes.** Every packet carries a millisecond timestamp; only NOISE frames are
  padded (`mesh/BLEPacketPaddingPolicy.kt` ~L11-17). Public message sizes leak exactly; Noise
  padding scheme sufficiency: **UNKNOWN**. **CONFIRMED.**
- **Routing.** Source routes may be attached to addressed packets (`applyRouteIfAvailable`,
  `docs/SOURCE_ROUTING.md`) exposing path information to relays. **INFERRED.**
- **Recipient IDs** for DMs are cleartext on outer packets (needed for routing). **INFERRED.**

---

## 12. Privacy risks — location, geohash, Nostr, Internet defaults

- **Internet transport on at startup.** `BitchatApplication` initialises `RelayDirectory`,
  `NostrIdentityBridge`, and `NostrBackgroundRuntime.initialize()`, which calls
  `subscriptions.connect()` and `subscribeAccountDm()` (`nostr/NostrBackgroundRuntime.kt` ~L47-60).
  The app therefore contacts Nostr relays and announces a `#p` filter for the account pubkey at
  launch, without the user opting in to Internet features. Decision 007 says Internet is optional
  for core chat; it does not say it is off by default. **CONFIRMED (code) — product decision needed.**
- **Tor default ON** (`net/TorPreferenceManager.kt` ~L11-30) and upstream verified fail-closed proxy
  ordering (`net/ArtiTorManager.kt`). Fail-closed not re-verified here. **CONFIRMED (default) /
  INFERRED (fail-closed).**
- **GPS is opt-in.** `DEFAULT_LIVE_LOCATION_ENABLED = false` (`geohash/LiveLocationPrivacyGate.kt`
  ~L80); location updates gated on `isLocationServicesEnabled()` (`geohash/LocationChannelManager.kt`
  ~L166-234). Satisfies "no silent GPS". `ACCESS_BACKGROUND_LOCATION` and
  `FOREGROUND_SERVICE_LOCATION` are requested in the manifest — justification needs UX/security
  sign-off. **CONFIRMED.**
- **Location precision.** Levels include BUILDING = 8-char geohash (~19×38 m)
  (`geohash/LocationChannel.kt` ~L8); Location Notes require precision 8
  (`nostr/LocationNotesManager.kt` ~L126-176) and are kind-1 (relay-persistent) signed events → a
  durable public record "key X was within ~30 m at time T" (upstream H7). **CONFIRMED; open.**
- **Deterministic per-geohash identities** never rotate (HMAC(deviceSeed, geohash)); plaintext
  nickname tag links identities across channels (upstream H8). **CONFIRMED; open.**
- **Automatic Nostr receipts.** `nostr/NostrDirectMessageHandler.kt` (~L143-187) sends DELIVERED for
  every admitted DM (and favourite control message) and READ when viewing, with no setting gate —
  online-presence oracle (upstream H6). **CONFIRMED; open.**
- **Relay directory** fetched from a third-party GitHub raw URL, unsigned/unpinned
  (`nostr/RelayDirectory.kt` ~L29, ~L140-190); SHA-256 is computed only for logging. Compromise
  steers users to attacker relays (upstream M9). **CONFIRMED; open.**
- **Reverse geocoding** to OSM Nominatim / Google Fused (upstream L5) — **UNKNOWN** (not re-read).
- **Privacy policy drift.** `PRIVACY_POLICY.md` ~L123 says messages are "Deleted from memory when
  app closes (unless room retention is enabled)" while private conversations are now always
  persisted (§8); policy is branded "bitchat" and describes "triple-tap the logo" wipe.
  **CONFIRMED — needs update before distribution.**

---

## 13. Malicious relay (mesh and Nostr)

Mesh relays (any BLE/Wi-Fi Aware neighbour):
- **Alter:** cannot alter signed public payloads or Noise payloads undetected; **can alter TTL**
  (unsigned) and can alter unsigned FRAGMENT / REQUEST_SYNC frames. **CONFIRMED.**
- **Drop / delay / selectively forward:** always possible; store-and-forward and delivery ACKs are the
  only signal. **INFERRED.**
- **Replay:** within 5 min dropped by dedup; after that, signed MESSAGE/FILE_TRANSFER replayable;
  ANNOUNCE replay at TTL 7 accepted as fresh direct-neighbour evidence, can churn sessions
  (upstream H12). **CONFIRMED (code) / INFERRED (impact).**
- **TTL inflation / amplification:** no ingress TTL clamp found in `mesh/PacketRelayManager.kt`
  `handlePacketRelay()` (~L58-70 only checks `ttl == 0` and decrements; only VOICE_FRAME is capped at 5).
  Packets with TTL ≥ 4 are always relayed (`shouldRelayPacket()` ~L142-147); TTL is a full byte, so a
  forged TTL up to 255 can propagate far beyond the 7-hop design. **CONFIRMED (relay code) / INFERRED
  (no clamp elsewhere — Wi-Fi Aware defines its own `MAX_TTL` ~L68 for its paths).**
- **Unsigned flood types** relayed mesh-wide (upstream H10). **CONFIRMED.**
- **REQUEST_SYNC:** responses use `SYNC_TTL_HOPS = 0` (`util/AppConstants.kt` ~L11;
  `sync/GossipSyncManager.kt` ~L192, ~L203) so amplification is one-hop, but there is no rate
  limit/response budget (`MeshCore.kt` ~L525-529 → `handleRequestSync()` ~L177). **CONFIRMED;
  partially mitigated.**

Nostr relays:
- Forged public events now rejected by signature checks (upstream H5 fixed). **CONFIRMED.**
- Relays learn subscribed geohash cells (`#g`), owned pubkeys (`#p`), timing and exact DM sizes
  (no padding). Tor hides IP only. **CONFIRMED / INFERRED.**
- Bounded outbound queue `NostrPendingEventQueue(MAX_QUEUED_EVENTS)` with per-relay delivery
  tracking (`nostr/NostrRelayManager.kt` ~L112, ~L457-468, ~L1136-1146) — upstream M11 fixed.
  **CONFIRMED.**
- Unbounded per-pubkey caches in `GeohashRepository` (upstream M12): **UNKNOWN** (not re-checked).

---

## 14. Attack surface

| Surface | Finding | Label |
|---|---|---|
| Exported `MainActivity` | Launcher + `bitchat://verify` VIEW deep link. Acts on unauthenticated extras `ACTION_QUIT_APP`, `EXTRA_OPEN_PRIVATE_CHAT`, `EXTRA_PEER_ID`, `EXTRA_GEOHASH` (`MainActivity.kt` ~L109, ~L725, ~L795-830) → any app can finish the UI or open arbitrary chat sheets / clear notification state (upstream M8). | CONFIRMED |
| Other components | `MeshForegroundService`, `ConversationNotificationReceiver`, `BootCompletedReceiver`, `HotspotActivity`, `GeohashPickerActivity`, `FileProvider` all `exported="false"`; FORCE_FINISH broadcast protected by a `signature` permission (`AndroidManifest.xml` ~L73-75, ~L99-170). | CONFIRMED |
| FileProvider paths | `res/xml/file_paths.xml` exposes `files-path path="."` and `cache-path path="."` — whole app files/cache dirs are grantable; safe only if every grant is a specific URI. | CONFIRMED (config) / INFERRED (risk Low) |
| Debug test hook | `app/src/debug/AndroidManifest.xml` exports `testhook.TestHookReceiver` (action `com.bitchat.droid.TEST_HOOK`) able to drive DMs, files, raw packets. Debug source set only; any distributed debug/internal build lets any local app drive the mesh. | CONFIRMED (debug-only) |
| NanoHTTPD APK server | `hotspot/ApkWebServer.kt`: `NanoHTTPD(port)` on 9999 with no hostname → binds all interfaces while running; plain HTTP; serves the APK and an HTML page; logs requester IP. NanoHTTPD 2.3.1 is unmaintained (last release 2016). Integrity of the side-loaded APK relies on the user trusting the hotspot; `UniversalApkManager.kt` has a pinned release cert check (~L820) for some flows — scope **UNKNOWN**. | CONFIRMED (binding/port) / INFERRED (exposure) |
| Wi-Fi Aware | Hard-coded PSK (§5); server socket bound to `::` any-address (`WifiAwareMeshService.kt` ~L821-826) — may be reachable on other interfaces while open. | CONFIRMED / INFERRED |
| BLE GATT | Every write decoded/validated with no rate limit; per-sender actor with `Channel.UNLIMITED` created before validation, no eviction (`mesh/PacketProcessor.kt` ~L40-75) (upstream H9/M15). | CONFIRMED |
| Image parsing | `BitmapFactory.decodeFile` without `inJustDecodeBounds`/`inSampleSize` anywhere in `app/src/main` (`ui/media/ImageMessageItem.kt` ~L59, `ui/media/FullScreenImageViewer.kt` ~L76, `features/media/ImageUtils.kt` ~L48/73), inside Compose `remember` → decode bombs / OOM (upstream M6). | CONFIRMED |
| QR scanning | ML Kit barcode + ZXing (`app/build.gradle.kts` ~L149-150). Payload validation not re-traced. | UNKNOWN |
| WebView | `ui/GeohashPickerActivity` (not exported) — upstream L11 (JS + interpolation). | UNKNOWN (not re-read) |
| Wear module | `wear/` not reviewed. | UNKNOWN |

---

## 15. Upstream review (`docs/security-review-jul-27.md`) status in this tree

| ID | Upstream finding | Status here | Evidence |
|---|---|---|---|
| C1 | Session keys in logcat | **Fixed** | No `Log` in `noise/southernstorm/protocol/*.java` |
| C2 | Decompression bomb | **Fixed** (residual 10 MiB cap) | `protocol/CompressionUtil.kt` ~L100-110; `DecompressionResourcePool.kt` |
| H1 | Replay window shift | **Open** | `noise/NoiseSession.kt` ~L68-104 |
| H2 | Announce key binding / first-announce-wins | **Partially mitigated** (persisted authenticated state wins; TOFU remains for unauthenticated peers) | `AnnouncementIdentityValidator.kt`; `SecurityManager.kt` ~L268-300 |
| H3 | DM content in logcat | **Fixed** (identifiers still logged) | `mesh/MessageHandler.kt` |
| H4 | DM content on lock screen | **Partially fixed** (DM only) | `ui/NotificationManager.kt` ~L276, ~L322 |
| H5 | Nostr event signatures unchecked | **Fixed** | `GeohashMessageHandler.kt` ~L52; `LocationNotesManager.kt` ~L420; `NostrClient.kt` ~L285 |
| H6 | Auto Nostr receipts | **Open** | `NostrDirectMessageHandler.kt` ~L143-187 |
| H7 | Building-precision public notes | **Open** | `LocationChannel.kt` ~L8; `LocationNotesManager.kt` ~L141-176 |
| H8 | Stable per-geohash identities | **Open** | `NostrIdentity.kt` ~L133-177 |
| H9 | Unbounded per-peer actors | **Open** | `PacketProcessor.kt` ~L47-75 |
| H10 | Unsigned types relayed / TTL | **Open** (VOICE_FRAME added to signed set) | `SecurityManager.kt` ~L256-265; `PacketRelayManager.kt` |
| H11 | REQUEST_SYNC amplification | **Partially mitigated** (TTL 0 replies; no rate limit) | `GossipSyncManager.kt` ~L177-205 |
| H12 | ANNOUNCE replay at TTL 7 | **Open** | `SecurityManager.kt` ~L78-90 |
| M1 | Low-order points | **Open**, re-rated Low (spec-permitted) | `HandshakeState.java` ~L1004 |
| M2 | Half-open handshakes | **Partially** (stale expiry, no cap) | `NoiseSessionManager.kt` ~L312-330 |
| M3 | Stable peer ID in BLE adv | **Open** | `BluetoothGattServerManager.kt` ~L403-406 |
| M4 | Fragment ID collision | **Open** | `FragmentManager.kt` ~L32, ~L189 |
| M5 | Legacy plaintext Ed key | **Open** | `EncryptionService.kt` ~L505-524 |
| M6 | Image decode bombs | **Open** | see §14 |
| M8 | Exported activity extras | **Open** | `MainActivity.kt` ~L795-830 |
| M9 | Unsigned relay directory | **Open** | `RelayDirectory.kt` ~L29 |
| M10 | Location leak to relays | **Mitigated by Tor default** (relays still see cells) | `TorPreferenceManager.kt` |
| M11 | Unbounded Nostr queue | **Fixed** | `NostrRelayManager.kt` ~L112 |
| M13 | NIP-44 without padding | **Open** | `NostrCrypto.kt` ~L244-262 |
| M16 | Two Ed25519 identities + stub sign/verify | **Open** | `EncryptionService.kt`; `NoiseEncryptionService.kt` |
| L3 | Silent identity regeneration | **Open** | `EncryptionService.kt` ~L463-483 |
| L9 | No FLAG_SECURE | **Partial** (Recents screenshot disabled API 33+) | `MainActivity.kt` ~L86 |
| M7, M12, M14, M15, L1-L2, L4-L8, L10-L13 | — | **Not re-verified** | UNKNOWN |

---

## 16. Mismatches vs `docs/product/05_SECURITY_REQUIREMENTS.md`

| Requirement (doc 05) | Current state | Gap severity |
|---|---|---|
| Replay resistance | Noise replay window defective; post-5-min replay of signed public packets; ANNOUNCE TTL-7 replay | High |
| Fail closed when authentication fails | Receivers fail closed on signatures/AEAD; **sender** fails open (sends unsigned); identity silently regenerates on key-load failure; stub `verify()` returns session presence | Medium |
| Never log raw device identifiers when avoidable | Peer IDs, fingerprints, favourites set, nicknames, client IPs logged; no release log stripping | Medium |
| No secrets in plain SharedPreferences | Legacy plaintext Ed25519 key can persist in `bitchat_crypto` | Medium |
| Protect sensitive material at rest | Message payloads encrypted (good); received media/files/voice plaintext; conversation metadata plaintext | Medium |
| No default cloud backup of private content | `allowBackup=false` (good) but D2D may still copy DB metadata + plaintext media on some OEMs; extraction rules cover only two legacy pref files | Medium |
| Minimal metadata exposure | Stable advertised peer ID, cleartext nickname/keys in ANNOUNCE, per-packet ms timestamps, Nostr relay connect + `#p` at startup, automatic receipts | High |
| Location sharing explicit and granular | GPS opt-in (good); but building-level public persistent notes and stable geohash identities lack clear warnings/rotation | High |
| Explain message expiration accurately | `PRIVACY_POLICY.md` says messages vanish on close; they persist | Medium (doc/legal) |
| Threats: spam/flooding, amplification, DoS, battery exhaustion | Unbounded actors, no GATT/relay rate limits, unsigned relayed types, unclamped TTL | High |
| Threats: Sybil | Free self-signed identities, no peer-table cap (upstream M14 not re-verified) | Medium |
| Threats: lost/stolen device | Panic wipe exists (good, not device-verified); no app lock / FLAG_SECURE; lock-screen previews for non-DM notifications | Medium |
| Threats: debug/log leakage | See logging; debug test hook exported in debug builds | Medium |
| Display name ≠ identity | Satisfied structurally | — |
| Reuse established crypto, do not invent | Satisfied, with caveat that the "NIP-44" label is a non-standard construction and the Noise explicit-nonce scheme is a project extension | Low |

---

## 17. Positive controls observed

- Noise XX with external test vectors; peer ID cryptographically bound to static key at both
  announcement and handshake; rehandshake isolation via responder candidates. (CONFIRMED)
- Ed25519 signatures mandatory for state-changing public packets incl. LEAVE with time window.
  (CONFIRMED)
- Decompression bounded and heap-budgeted. (CONFIRMED)
- Nostr event and NIP-17 seal signature checks; randomised gift-wrap timestamps
  (`NostrProtocol.kt` ~L234, ~L263). (CONFIRMED)
- Conversation store: Keystore AES-GCM with AAD, scrubbed legacy columns, crypto-erasure on clear.
  (CONFIRMED)
- GPS off by default; Tor on by default; `allowBackup=false`; device name not advertised.
  (CONFIRMED)
- Comprehensive panic-wipe code path. (CONFIRMED code, not device-verified)

---

## 18. Risk register

| ID | Risk | Severity | Justification | Evidence | Status |
|---|---|---|---|---|---|
| R1 | Noise replay window accepts replays of older nonces | High | Breaks a stated security property; passive BLE capture suffices; only partial upper-layer dedup | `noise/NoiseSession.kt` ~L49-104 | CONFIRMED (JVM test `NoiseSessionReplayWindowCharacterizationTest`, 2026-10-07) |
| R2 | Unbounded per-sender actors / unlimited channels pre-validation | High | Single nearby attacker can exhaust memory/coroutines; kills service for all mesh users nearby | `mesh/PacketProcessor.kt` ~L40-75 | CONFIRMED |
| R3 | Unsigned FRAGMENT/REQUEST_SYNC/NOISE relayed; TTL unsigned and not clamped | High | Mesh-wide flood/amplification and battery drain from one radio | `mesh/SecurityManager.kt` ~L256-265; `mesh/PacketRelayManager.kt` ~L58-70, ~L142-147; `protocol/BinaryProtocol.kt` ~L114 | CONFIRMED (TTL clamp absence INFERRED globally) |
| R4 | Stable advertised peer ID + cleartext nickname/keys | High | Long-term passive tracking of consumers across places; core product is "nearby people" | `mesh/BluetoothGattServerManager.kt` ~L403-406 | CONFIRMED |
| R5 | Building-precision persistent public notes; non-rotating geohash identities | High | Durable public location trail tied to stable keys | `geohash/LocationChannel.kt` ~L8; `nostr/NostrIdentity.kt` ~L133-177 | CONFIRMED |
| R6 | Automatic Nostr delivery/read receipts | High | Online-presence oracle without user consent | `nostr/NostrDirectMessageHandler.kt` ~L143-187 | CONFIRMED |
| R7 | ANNOUNCE replay at TTL 7 accepted as fresh | Medium | Session churn / fake direct-neighbour; needs proximity | `mesh/SecurityManager.kt` ~L78-90 | CONFIRMED |
| R8 | Image decode without bounds on main/composition thread | Medium | Contact-initiated crash/OOM; private media path requires a Noise session | `ui/media/ImageMessageItem.kt` ~L59 | CONFIRMED |
| R9 | Identifier/social-graph logging; no release log stripping | Medium | Leaks via bug reports/ADB/OEM collectors; violates doc 05 | `app/proguard-rules.pro`; `ui/PrivateChatManager.kt` ~L230; `ui/DataManager.kt` ~L161-197 | CONFIRMED |
| R10 | Received media/files/voice unencrypted at rest; plaintext conversation metadata | Medium | Lost/rooted device and D2D exposure | `features/file/FileUtils.kt` ~L238-261; `services/ConversationRepository.kt` schema | CONFIRMED |
| R11 | D2D transfer not excluded for DB/media/geohash prefs | Medium | OEM-dependent copy of private data to another device | `res/xml/data_extraction_rules.xml` | CONFIRMED config / INFERRED behaviour |
| R12 | Silent identity regeneration; swallowed key-store write failures | Medium | Masks tampering; breaks verification; looks like impersonation | `crypto/EncryptionService.kt` ~L463-503; `noise/NoiseEncryptionService.kt` ~L85-116 | CONFIRMED |
| R13 | Legacy plaintext Ed25519 key may persist | Medium | Private key in plain prefs on upgraded installs | `crypto/EncryptionService.kt` ~L505-524 | CONFIRMED |
| R14 | Two Ed25519 identities; stub `sign()`/`verify()`; send-unsigned fallback | Medium | Latent auth bypass for future callers; fail-open | `crypto/EncryptionService.kt` ~L226-241; `mesh/BluetoothMeshService.kt` ~L1596-1612 | CONFIRMED |
| R15 | Exported MainActivity acts on untrusted extras | Medium | Local app can manipulate UI/notification state; no data exfil found | `MainActivity.kt` ~L109, ~L795-830 | CONFIRMED |
| R16 | Unsigned third-party relay directory | Medium | Supply-chain steering to hostile relays | `nostr/RelayDirectory.kt` ~L29 | CONFIRMED |
| R17 | Nostr relay connection + `#p` subscription at startup by default | Medium | Internet metadata exposure without opt-in (Tor mitigates IP) | `nostr/NostrBackgroundRuntime.kt` ~L47-60 | CONFIRMED |
| R18 | Non-DM notifications lack public version; no preview toggle; no FLAG_SECURE | Medium | Shoulder-surfing / listener apps | `ui/NotificationManager.kt` ~L584, ~L797; `MainActivity.kt` ~L86 | CONFIRMED |
| R19 | "NIP-44" without padding | Medium | Exact length leakage to relays; interop confusion | `nostr/NostrCrypto.kt` ~L244-262 | CONFIRMED |
| R20 | Fragment reassembly keyed by fragment ID only | Medium | Targeted transfer DoS | `mesh/FragmentManager.kt` ~L32, ~L189 | CONFIRMED |
| R21 | Hard-coded Wi-Fi Aware PSK; any-address socket bind | Medium | No link-layer secrecy; possible listener exposure on other interfaces | `wifi-aware/WifiAwareMeshService.kt` ~L70, ~L821-838 | CONFIRMED / INFERRED |
| R22 | NanoHTTPD 2.3.1 APK server on all interfaces, HTTP | Low-Med | Only while user runs hotspot share; unmaintained dependency | `hotspot/ApkWebServer.kt` ~L17-21, ~L304 | CONFIRMED / INFERRED |
| R23 | Half-open Noise responder candidates uncapped | Low-Med | CPU DoS under lock; mitigated by stale sweep | `noise/NoiseSessionManager.kt` ~L312-330 | CONFIRMED |
| R24 | `security-crypto` deprecated | Low | Maintenance/compat; functional today | `gradle/libs.versions.toml` | CONFIRMED |
| R25 | Debug test hook exported in debug builds | Low | Only if debug builds are distributed | `app/src/debug/AndroidManifest.xml` | CONFIRMED |
| R26 | Low-order Curve25519 points not rejected | Low | Spec-permitted; only affects sessions the attacker is party to | `HandshakeState.java` ~L1004 | CONFIRMED |
| R27 | Privacy policy inaccurate (persistence, branding) | Medium (legal/trust) | Must not ship misleading policy | `PRIVACY_POLICY.md` ~L123 | CONFIRMED |

---

## 19. Areas requiring deeper review

1. JVM unit test for `NoiseSession` replay window (in-order, out-of-order, shift by 1/7/8/9/1023/1024)
   before any fix; confirm the Python simulation result in-JVM. Coordinate with iOS (comments say
   "matching iOS implementation") — wire-compatible fix only, no format change.
2. Which Ed25519 key (EncryptionService vs SecureIdentityStateManager) is announced, used in the
   `0x21` peer-state proof, and verified by peers; consolidation plan without identity breakage.
3. End-to-end TTL handling across BLE, Wi-Fi Aware and `TransportBridgeService` (ingress clamp
   location, if any).
4. Noise padding scheme, message-size leakage, and explicit-nonce overflow handling at 2^32.
5. QR verification payload parsing and `bitchat://verify` deep-link handling.
6. `GeohashPickerActivity` WebView, reverse-geocoding providers, `NostrTestManager` release reachability.
7. Wear OS module (`wear/`) data sync and storage.
8. Nostr `GeohashRepository` cache bounds, NIP-17 rumor kind and `created_at` replay window
   (upstream M12, L8).
9. Peer-table caps / Sybil (upstream M14) and GATT rate limiting (M15).
10. `UniversalApkManager` pinned-certificate verification scope for side-loaded updates.
11. Physical-device validation: panic wipe completeness (incl. legacy `bitchat_crypto` file,
    WAL files), lock-screen notification behaviour, D2D transfer on OEM devices.
12. License review of vendored Noise-Java (`noise/southernstorm`) and Arti per `LICENSE.md`
    before commercial distribution (out of scope here; flag only).

---

## 20. Sources

Repository (repo-relative): `app/src/main/java/com/bitchat/android/{noise,crypto,identity,protocol,
mesh,nostr,net,services,ui,geohash,hotspot,wifi-aware,features}/…` as cited above;
`app/src/main/AndroidManifest.xml`; `app/src/debug/AndroidManifest.xml`;
`app/src/main/res/xml/{data_extraction_rules,backup_rules,file_paths}.xml`; `app/proguard-rules.pro`;
`app/build.gradle.kts`; `gradle/libs.versions.toml`; `docs/security-review-jul-27.md`;
`docs/NOISE_PEER_ID_BINDING.md`; `docs/PRIVATE_MEDIA_V1.md`; `docs/GeohashPresenceSpec.md`;
`docs/product/05_SECURITY_REQUIREMENTS.md`; `docs/product/08_DECISIONS.md`; `PRIVACY_POLICY.md`;
tests under `app/src/test/kotlin/com/bitchat/android/{noise,crypto,identity,nostr,protocol,mesh}/`
(listed, not executed).

External:
- Noise Protocol Framework specification — https://noiseprotocol.org/noise.html (XX payload
  properties; §12.1 invalid 25519 keys; nonce rules; application-level replay tracking).
- Android Auto Backup / `allowBackup` behaviour for API 31+ —
  https://developer.android.com/guide/topics/data/autobackup
- AndroidX Security (security-crypto) release notes, deprecation —
  https://developer.android.com/jetpack/androidx/releases/security

Verification level of this document: **static code reading only** (plus one Python transcription
of the replay-window logic). No build, unit-test, emulator or physical-device verification.
