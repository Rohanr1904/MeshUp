# MeshUp — Target Architecture (evidence-based recommendations)

> Last updated: 2026-10-07 (rev 2) · Baseline `8d7de5e` · Status: **RECOMMENDED**. None of this is adopted until it is recorded in `docs/product/08_DECISIONS.md`.
> Every recommendation cites current-state evidence. Assumptions are labelled **ASSUMPTION**.
> Governing rules: Decision 002 (do not rewrite the mesh); doc 10 stop conditions (changes to protocol, crypto, DB migrations or transports need explicit approval).
> Rev 2: the key recommendations are restated in the architecture-decision standard (§3). The phase plan (§4) has exit criteria. The evidence was re-verified against the code (§6).

---

## 0. Assumptions (must be confirmed by the product owner)
- **A1 Wire compatibility:** MeshUp stays wire-compatible with BitChat clients (iOS/Android) in v1. Protocol-visible changes (advertised-ID rotation, any new packet type) are coordinated, not unilateral. *If rejected, relay/ID changes get cheaper, but MeshUp forms its own, smaller network.*
- **A2 Licence:** MeshUp is distributed under GPLv3 with source, because `LICENSE.md` is GPLv3. *If a proprietary model is wanted, stop and get legal advice first (Decision 010).*
- **A3 Internet opt-in:** Tor, Nostr and geohash stay in the product but become opt-in (they are currently initialised unconditionally in `BitchatApplication.onCreate`).
- **A4 Wear:** the Wear module is out of scope for v1 but must keep compiling (it shares source with `app`).

## 1. Principle: wrap, don't rewrite
Keep `mesh/`, `protocol/`, `noise/`, `crypto/`, `sync/` and `wifi-aware/` as the **engine**. Build MeshUp as new layers above the existing `MeshService` / `MeshDelegate` seam (CONFIRMED to be the UI↔mesh contract: `mesh/MeshService.kt`, `mesh/MeshDelegate.kt`). Engine fixes are small, test-first and security-reviewed.

```
            ┌──────────────────────── MeshUp app layer (new) ───────────────────────┐
 Compose    │ AppShell (NavHost: Home · Chats · Rooms · People · Profile · Diagnostics)│
 UI         │ feature screens ── feature ViewModels (small, interface-injected)        │
            ├──────────────────────────── Domain (new, thin) ────────────────────────┤
            │ MessagingService   RoomService   PeopleService   PrivacySettings        │
            │ DeliveryTracker (state machine)  Outbox (durable)                       │
            ├────────────────────────── Data (extend existing) ──────────────────────┤
            │ ConversationRepository (SQLite v4, AES-GCM) + outbox/rooms tables (new) │
            │ Settings store (consolidated)   AppStateStore (read-model cache only)   │
            ├──────────────────────── Engine (preserve, inherited) ──────────────────┤
            │ MessageRouter ─ UnifiedMeshService ─ BLE / Wi-Fi Aware ─ Noise ─ Nostr  │
            └─────────────────────────────────────────────────────────────────────────┘
```

Dependency rule: UI → Domain → Data/Engine. Engine never imports UI/Domain. The wire models (`BitchatPacket`, `BitchatMessage`) do not leak above the Domain layer for new code.

## 2. Recommendation index

| ID | Recommendation | Value | Risk | Stop condition? | Phase |
|---|---|---|---|---|---|
| R-1 | Durable outbox + delivery state machine | Very high | Medium | Yes (DB migration v4→v5) | 2 |
| R-2 | Domain/UI model split | High (enabler) | Low | No | 1 |
| R-3 | App shell, navigation, onboarding, feature VMs | Very high | Low | No | 1 |
| R-4 | Rooms = channels + local metadata | High | Medium | Password fix = crypto-adjacent review | 3 |
| R-5 | Engine hardening (replay, actors, TTL, signing, logs, media) | High (security) | Low–Med each | Security review per item | 0.5 (tests) → 2 (fixes) |
| R-6 | Product-owned privacy/network settings | High | Low | No (A3 decision) | 1–2 |
| R-7 | Relay-side store-and-forward | High | High | Yes (protocol/security) | Design only, after R-1 |
| R-8 | BLE/MeshCore transport unification | Medium | High | Yes (transport) | Deferred |
| R-9 | Android platform compliance (permissions, FGS) | Medium | Low | No | 2 |
| R-10 | Release re-identity | Required for release | Medium | Licence gate | Pre-release |

## 3. Decision records (architecture-decision standard)

### R-1 Durable outbox + delivery state machine
- **Problem:** messages queued for an absent peer are lost when the process dies; delivery states lie after a restart.
- **Evidence (CONFIRMED):** `services/MessageRouter.kt:84` `outbox = ConcurrentHashMap` (RAM only). Rows persisted as `Sending` are never re-queued (TD-01). The delivery ACK is single-shot with no timeout resend (TD-02). `ConversationRepository` has `DATABASE_VERSION = 4` and `private_messages.message_id TEXT UNIQUE`.
- **Current behaviour:** a DM to an offline peer waits in RAM. It is flushed on peer connect or favourite-Nostr availability, and dropped on process death. The UI may show `Sending` forever.
- **Proposed change:** add an `outbox` table to the existing encrypted SQLite DB (migration v4→v5). `MessageRouter` writes before it sends, deletes on ACK/terminal failure, and rehydrates on start. Add a `DeliveryTracker` state machine: `Queued → Sending → Sent → Delivered → Read | Failed(reason)`, with a bounded ACK-timeout resend that reuses the same `messageID`.
- **Alternative considered:** (a) Room/ORM migration: rejected, because it doubles the migration risk on a live encrypted schema. (b) WorkManager-only queue: rejected as the primary store, because the DB must be the source of truth (PRD "Reliability"). WorkManager can still be a wake-up trigger later.
- **Trade-off:** one more write per send, plus migration code to maintain. In return: honest delivery states and survival across process death.
- **Compatibility impact:** none on the wire (it reuses the existing `DELIVERED` ACK and `messageID`; receivers already dedup by `message_id UNIQUE`). The DB schema version bumps, so a downgrade is unsupported.
- **Security impact:** outbox payloads must use the same Keystore AES-GCM wrapping as `private_messages`. Resends must not create new nonces outside Noise (Noise handles this). Bound queue size per peer to avoid storage-exhaustion DoS.
- **Testing required:** migration tests v4→v5 (fresh + upgrade + corrupted). `MessageRouter` restart characterization test (write it failing in Phase 0.5). State-machine unit tests. Duplicate-resend dedup test. Physical 2-device test: kill the app mid-send, reopen, peer arrives, message is delivered once.

### R-2 Domain/UI model split
- **Problem:** UI, persistence and wire share one model, so product fields (room, expiry, hop count) would leak into the codec.
- **Evidence (CONFIRMED):** TD-13: `BitchatMessage` is both `Parcelable` (UI) and the binary wire codec.
- **Current behaviour:** UI code consumes `BitchatMessage` directly.
- **Proposed change:** new `ChatMessage`/`Room`/`Person` domain models with mappers at the Data/Engine boundary. New screens use only domain models. Existing `ChatScreen` keeps working unchanged.
- **Alternative considered:** add fields to `BitchatMessage`. Rejected: this risks a wire-format change (A1) and Gson-persistence breakage.
- **Trade-off:** mapper boilerplate. In return, a stable wire format and freedom in the UI.
- **Compatibility impact:** none (additive).
- **Security impact:** reduces the risk of accidentally serialising local-only fields onto the wire.
- **Testing required:** mapper round-trip unit tests; existing codec tests stay green.

### R-3 App shell, navigation and onboarding (Phase 1 enabler)
- **Problem:** the product's core experience (name → discover → rooms → chat) has no screens to live in.
- **Evidence (CONFIRMED):** a single `ChatScreen` + sheets. `navigation-compose` is declared but unused. There is no display-name onboarding step (TD-10…TD-12).
- **Current behaviour:** permissions onboarding → one chat surface. Rooms are `/join` commands.
- **Proposed change:** a `NavHost` shell (Home · Chats · Rooms · People · Profile). Add a display-name onboarding step that writes the existing nickname store, keeping the identity key separate (Decision 005). Small feature ViewModels depend on interfaces (`MessagingService`, `PeopleService`) that are implemented first as adapters over the existing `ChatViewModel` managers and `AppStateStore` flows (strangler pattern). Manual DI via one `AppContainer`.
- **Alternative considered:** (a) Hilt: deferred, because it costs build complexity under STRICT dependency locking and verification metadata. (b) Rewrite `ChatViewModel`: rejected, because it is too large and untested to rewrite safely.
- **Trade-off:** there will temporarily be two UI paths (new shell + legacy `ChatScreen`).
- **Compatibility impact:** none on the wire. Adding `navigation-compose` usage needs no new dependency, but lockfiles must be re-verified if transitive deps change.
- **Security impact:** onboarding must not log the display name and must not alter the identity key. The display name is broadcast in ANNOUNCE (existing behaviour), so the UI copy must say it is public.
- **Testing required:** first Compose UI tests (there are currently 0 instrumented tests). Adapter unit tests. Manual emulator run of the onboarding → chat path.

### R-4 Rooms = channels + local metadata
- **Problem:** rooms are a primary experience (Decision 006) but exist only as slash commands, and the password protection is fake.
- **Evidence (CONFIRMED):** `ui/ChannelManager.kt:125` `verifyChannelPassword` returns `true` (`// TODO: REMOVE THIS - FOR TESTING ONLY`). PBKDF2 key derivation exists alongside it.
- **Current behaviour:** any password "verifies". Channel content protection relies only on key derivation.
- **Proposed change:** local room metadata table (description, visibility, expiry, retention), enforced locally. Implement real verification (attempt to decrypt a known channel message/key-commitment under the derived key) **before** surfacing "password rooms" in the UI. Defer invite-only rooms to a security design.
- **Alternative considered:** a new room packet type. Rejected for v1 (A1, protocol change).
- **Trade-off:** room discovery/member counts are approximate (they come from observed traffic).
- **Compatibility impact:** none if verification uses existing channel crypto. A key-commitment field would be a wire change and needs A1 sign-off.
- **Security impact:** removes a fail-open authentication stub. Retention copy must say that expiry is local-only (doc 05).
- **Testing required:** a password right/wrong unit test, retention purge tests, and an interop check with an upstream BitChat client.

### R-5 Engine hardening backlog (test-first, each item security-reviewed)
Common fields: **Alternative considered:** leave the issue as is or document it as a known issue. **Trade-off:** a small engine diff vs a stated security property. **Testing required:** a failing JVM test lands first (Phase 0.5), then the fix in a separate PR.

| # | Problem / evidence | Proposed change | Compat impact | Security impact |
|---|---|---|---|---|
| 5.1 | **Replay window** (R1, CONFIRMED by hand-trace rev 2): `noise/NoiseSession.kt:80-92` shifts the bitmap the wrong way (`ushr` within byte, carry from the lower byte). After a 1-step advance, the previous nonce's bit lands at offset 15, not 1, so a replay of `highest-1` is accepted. | Correct the shift (`shl` within byte + carry `ushr (8-r)` from the lower byte), or use a simpler `Long`-word bitset. No change to nonce encoding. | Receiver-only. None. | Restores Noise transport replay resistance. |
| 5.2 | Unbounded per-sender actors (R2). | LRU-bounded actor map + per-peer rate limit. | None. | DoS/battery-exhaustion mitigation. |
| 5.3 | **TTL not clamped** (CONFIRMED rev 2): `mesh/PacketRelayManager.kt:58-70,143-145` decrements any TTL and always relays when `ttl ≥ 4`. Only voice is capped (5). A forged TTL 255 floods the whole mesh. | On ingress clamp `ttl = min(ttl, 7)` (MAX_TTL) before the relay decision. | Local relay policy. Android senders use `AppConstants.MESSAGE_TTL_HOPS = 7` (CONFIRMED). iOS is INFERRED to send ≤7; verify before shipping. | Removes a message-amplification vector. |
| 5.4 | Sender fails open on signing; stub `sign()/verify()`; silent identity regeneration (R12–R14). | Fail closed; delete the stubs; surface identity reset to the user. | None. | Integrity/authenticity. |
| 5.5 | ~1.2k log calls; no release stripping (R9). | R8 `-assumenosideeffects` for `Log.v/d/i` + a redaction helper for IDs. | None. | Log-leakage mitigation. |
| 5.6 | Received media stored unencrypted (R10–R11). | Encrypt at rest; tighten backup/extraction rules. | Needs a migration. | Lost-device mitigation. |
| 5.7 | Stable advertised peer ID in scan response (R4). | **Defer**: needs a cross-client design. | Protocol-level. | Tracking/metadata. |

### R-6 Product-owned privacy and network settings
- **Problem:** the app connects to the Internet (Tor bootstrap + Nostr relays) on every launch, which contradicts "Internet is optional" and the privacy promise. Transport toggles are debug-only.
- **Evidence (CONFIRMED):** `BitchatApplication.kt:19-45` initialises `ArtiTorManager`, `RelayDirectory`, `LocationNotesInitializer` and the Nostr identity unconditionally (TD-20, TD-21).
- **Proposed change:** a `PrivacySettings`/`NetworkSettings` store with a master **Internet features** switch that gates that init block. It defaults to OFF for new installs (pending A3). Also: read-receipt toggles, notification previews, boot auto-start, and battery mode mapped to the existing `PowerManager` profiles plus a user PAUSED mode that stops the FGS.
- **Alternative considered:** strip Nostr/Tor entirely. Rejected: it loses the optional bridge, makes upstream merges harder and is irreversible.
- **Trade-off:** favourites-over-Nostr delivery stops working until the user opts in.
- **Compatibility impact:** none on the mesh wire. Nostr peers see the user offline when the switch is off.
- **Security impact:** large privacy gain (no IP/relay metadata by default). It must be verified with a network capture that nothing connects while OFF.
- **Testing required:** settings unit tests; emulator traffic check (no sockets opened at cold start with the switch OFF).

### R-7 Store-and-forward (after R-1): design only
Relay-side S&F is dead code (`mesh/StoreForwardManager.cacheMessage` has **no callers**, CONFIRMED rev 2). Sender-side durability (R-1) delivers "messages wait and retry" first. Carrying other people's encrypted DMs needs a decision record on quotas, spam, metadata and expiry. Do not just wire up the existing class.

### R-8 Transport unification (deferred)
BLE duplicates `MeshCore` instead of implementing `MeshTransport` (TD-07). It is the most-used and least-tested path. Prerequisite: BLE send/receive/relay characterization tests. Target: BLE implements `MeshTransport` and uses `MeshCore`, with one Noise session set per process.

### R-9 Android platform compliance
Remove the unused `READ_MEDIA_*` permissions and the unused FGS `dataSync` type. Reassess `ACCESS_BACKGROUND_LOCATION`. Evaluate `BLUETOOTH_SCAN neverForLocation` (Wear already does this) against the location FGS type. Validate boot/FGS start on API 34–37 **on physical devices**.

### R-10 Release re-identity (pre-release, licence-gated)
New `applicationId`, signing keys, cert pin (`BITCHAT_GITHUB_RELEASE_CERT_SHA256`), release/relay URLs and branding. Keep the Kotlin package, prefs/keystore/channel IDs and the `bitchat://verify` scheme (rename only with a migration and an interop decision).

## 4. Phase plan

| Phase | Scope | Exit criteria |
|---|---|---|
| **0.5 Baseline** | Make the local build reproducible (JDK 21). Run `testDebugUnitTest`, `lintDebug`, `:app:assembleDebug`, `:wear:assembleDebug`. Add **failing/characterization** tests only: NoiseSession replay window, PacketRelayManager TTL ingress, MessageRouter restart. Record the A1/A3/A4 decisions and the licence direction in `08_DECISIONS.md`. | Green baseline recorded. New tests fail for the documented reason. Decisions recorded. No production code changed. |
| **1 Shell + identity UX** | R-3 + R-2 (domain models for new screens only) + R-6 settings skeleton (switch wired only if A3 is approved). | App launches into the shell; onboarding sets the display name; legacy chat is reachable; first Compose tests; unit tests green; emulator smoke test. |
| **2 Reliability + hardening** | R-1 (after the migration plan is approved), R-5.1/5.3/5.2/5.4/5.5, R-9. | Phase 0.5 tests turn green; migration tests; 2+ physical-device delivery test after kill/restart. |
| **3 Rooms** | R-4. | Password rooms verified; local retention; interop check with upstream client. |
| Later | R-7 design, R-8, R-10, 5.6, 5.7. | Separate decision records. |

## 5. Non-goals for the target
- No rewrite of BLE, Noise, packet format or routing.
- No new crypto primitives.
- No Room/ORM migration of the existing SQLite store: extend it.
- No DI framework in Phase 1.

## 6. Re-verification log (rev 2, 2026-10-07)
Re-checked directly in code by the chief engineer: LICENSE GPLv3 vs README:30 / PRIVACY_POLICY:156 "public domain"; RAM-only outbox (`MessageRouter.kt:84`); password stub (`ChannelManager.kt:125`); replay-window shift direction (`NoiseSession.kt:80-92`); TTL ingress/relay policy (`PacketRelayManager.kt:58-70,143-145`); unconditional Tor/Nostr init (`BitchatApplication.kt:19-45`); `cacheMessage` has no callers; DB v4 + `message_id UNIQUE`; 95 JVM test files, 0 `androidTest` files. All were consistent with the rev-1 docs.

## Sources
- Current-state evidence: `docs/PROJECT_ANALYSIS.md`, `docs/MESH_ARCHITECTURE.md`, `docs/SECURITY_REVIEW.md`, `docs/TECHNICAL_DEBT.md`.
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/training/location/permissions
- https://noiseprotocol.org/noise.html
