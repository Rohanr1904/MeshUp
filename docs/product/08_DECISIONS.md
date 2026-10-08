# 08 — Architecture & Product Decisions

This document is the source of truth for important decisions.

## Decision 001 — Fork Android repository
Decision:
Use the Android implementation as the engineering foundation for the APK product.

Reason:
The target deliverable is Android. The Android repository already contains mesh, transport, protocol, crypto and UI work.

## Decision 002 — Do not rewrite the mesh
Decision:
Preserve proven networking/security components and extend them incrementally.

Reason:
Mesh networking and security are the highest-risk components.

## Decision 003 — Offline-first
Decision:
Core chat must function without Internet.

Reason:
Offline capability is the product's primary differentiator.

## Decision 004 — Local identity
Decision:
No central account is required for core messaging.

Reason:
Lower friction and stronger privacy.

## Decision 005 — Username is not identity
Decision:
Display name can change; cryptographic identity remains separate.

Reason:
Prevents trivial impersonation and supports verified contacts.

## Decision 006 — Rooms are first-class
Decision:
Nearby Rooms are a primary user experience.

Reason:
Rooms make mesh communication useful for groups and events.

## Decision 007 — Internet is optional
Decision:
Internet transports can enhance the product but must not be required for core offline chat.

## Decision 008 — Security over feature count
Decision:
Do not add features that undermine privacy or create unsafe protocol complexity.

## Decision 009 — Physical-device testing
Decision:
Radio behavior must be validated on physical Android devices.

## Decision 010 — Licensing is a release gate
Decision:
Commercial distribution must be reviewed against the actual upstream repository license and applicable obligations before release.

## Change policy
Any architecture/security/protocol decision must:
1. State the problem.
2. State alternatives.
3. State trade-offs.
4. Record compatibility impact.
5. Record migration/test requirements.

## Decision 011 — Licence: GPLv3 open source
Date: 2026-10-07 · Decided by: product owner
Decision:
MeshUp is distributed under GPLv3 with complete corresponding source, consistent with the inherited `LICENSE.md`.
Reason:
The upstream code is GPLv3 (switched from MIT upstream in `fb2bb64`). A proprietary model would require legal clearance first.
Follow-up:
`README.md:30` and `PRIVACY_POLICY.md:156` still say "public domain". Correcting those statements is a licence-text change: do it as its own reviewed change (doc 10 stop condition). Decision 010 remains the release gate (dependency licence inventory, notices).

## Decision 012 — Wire-compatible with BitChat in v1 (A1)
Date: 2026-10-07 · Decided by: product owner
Decision:
MeshUp v1 interoperates with existing BitChat iOS/Android clients on the same mesh.
Consequences:
Packet formats, BLE UUIDs, packet types and identity/announce formats stay unchanged. Receiver-side or local-policy hardening (replay window, ingress TTL clamp, rate limits) is allowed because it is invisible on the wire. Anything visible on the wire (advertised-ID rotation, new packet types, room key commitments) needs a cross-client design and a decision record.

## Decision 013 — Internet features are opt-in, default OFF (A3)
Date: 2026-10-07 · Decided by: product owner
Decision:
Tor, Nostr relays, geohash channels and location notes do not start until the user enables "Internet features". The default is OFF for new installs.
Consequences:
The startup init in `BitchatApplication` must be gated (TARGET R-6). Favourites-over-Nostr delivery works only after opt-in. The behaviour for existing installs (migration of the current always-on behaviour) needs a decision when R-6 is implemented. Verification: no Internet sockets at cold start with the switch OFF.
Addendum (2026-10-07, product owner, Phase 1 PR-2):
- Existing installs: no migration needed; MeshUp ships under a new applicationId (plan P1-7).
- Known exception (PR-2 review M3): ML Kit barcode-scanning (QR verification in `ui/VerificationSheet.kt`) bundles Google `datatransport` CCT telemetry, which uses its own HTTP stack outside the OkHttp gate and may send usage logs while Internet features are OFF. Not yet confirmed by network capture. Accepted for Phase 1; follow-up: replace ML Kit with an offline QR decoder (dependency change, own PR).

## Decision 014 — Wear OS out of scope for v1, must keep compiling (A4)
Date: 2026-10-07 · Decided by: product owner
Decision:
No Wear feature work in v1. `:wear:assembleDebug` stays green because `wear/build.gradle.kts` compiles shared sources from `app`.

## Decision 015 — Phase 2 delivery and identity parameters
Date: 2026-10-07 · Decided by: product owner
Decision:
- D1: a queued message for an absent peer expires to `Failed` after **1 hour** (was 24 h).
- D2: per-peer queue limit is **200**; overflow becomes `Failed("queue full")`.
- D3: after hand-off to the mesh with no delivery ACK, resend at **30 s, 1 min, 2 min, 2 min**, then `Failed`.
- D7: an unreadable identity key is never silently replaced; the user sees a warning with re-verify guidance and is not blocked. Any deliberate identity reset requires typing `reset` to confirm.
- D4–D6, D8–D11: the proposed defaults in `docs/IMPLEMENTATION_PLAN_PHASE2.md` §6.
Consequences:
Short expiry favours honest "Failed" states over long background waits; users retry manually (D5). Values live in `AppConstants` so they can be tuned after physical-device testing (Decision 009).
Amendment (2026-10-08, P2-PR8 review, default per owner preference): a message already handed to the mesh (SENT, awaiting ACK) whose row passes the 1 h bound is removed from the outbox **silently and keeps its status** (it may have been delivered with the ACK lost); only QUEUED messages that were never transmitted become `Failed("Not delivered")`. QUEUED rows count toward D2; SENT rows have their own 200-per-peer cap with oldest-silent eviction.
Amendment 2 (2026-10-08, P2-PR9, default): with no ACK, the first send plus four resends (+30 s, +1 m, +2 m, +2 m) are followed by one final 2 min wait, so `Failed("No delivery confirmation")` lands at about 7.5 min (not 5.5 min), giving the last resend time to be acknowledged. A late ACK still upgrades to Delivered; Retry (D5) resends with the same message ID.

## Decision 016 — Launch scope: defer non-blocking hardening, gate launch on devices, identity and licence
Date: 2026-10-08 · Decided by: product owner
Decision:
- Finish Phase 2 core before launch: P2-PR8 (durable router), P2-PR9 (ACK-timeout resend, Failed + Retry), P2-PR10 (manifest/permission compliance).
- **Deferred until after launch:** P2-PR11 (Arti native stop fix; the Settings restart prompt remains the mitigation, so D10 is deferred), the review follow-ups (voice relay delay and private-file save off the stripe consumers, type-specific caps for handshakes/announces, H12 announce dedup bypass, remaining full peer IDs in w/e logs, NoiseSession lock split / receive-side rekey count), and the ML Kit replacement (Decision 013 addendum).
- **Launch gates (must be done before public release):**
  1. Physical-device verification on 2–3 Android phones: exactly-once delivery after process kill, multi-hop relay, BLE discovery after the permission change (Decision 009).
  2. Own app identity: name, applicationId (replacing `com.bitchat.droid`), signing key under the owner's control (Play App Signing recommended), icon, privacy-policy URL; upstream identifiers removed (R-10).
  3. Licence: distribute under GPLv3 with source available and upstream attribution; README/PRIVACY_POLICY "public domain" statements corrected (Decision 010/011); owner confirms GPLv3 fits the business model, with legal advice if unsure.
  4. Release pipeline signed with the owner's key via repository secrets; Play forms (data safety, permission declarations) answered from the code.
- Phase 3 password rooms go before launch only if password rooms are wanted in v1 (the legacy password check is a stub).
- **Distribution (2026-10-08):** GitHub Releases plus a website download; **no Play Store**. Consequences: the owner holds the release signing key directly (no Play App Signing, so the key must be backed up securely; losing it prevents updates); the in-app update check and hotspot APK sharing stay (resolves D9); Play-specific forms and policies do not apply, but the privacy policy is still published on the website.
- **App name (2026-10-08): NearBird.** "MeshUp" was dropped because "Meshup: Make Moments" (Meshup Pte. Ltd., iOS, nearby social radar, May 2026) occupies a close space. A web and GitHub search found no messenger named NearBird (only unrelated bird-watching apps/repos); a formal trademark search and domain check remain before release.
- **Panic wipe (default, 2026-10-08):** the triple-tap emergency wipe stays instant, with no typed confirmation; D7's typed `reset` applies to the deliberate Profile → Reset identity action only.
- **Owner preference:** product decisions use the proposed defaults; Claude records and reports them. Legal, irreversible-public and credential actions still need the owner.
- **Application ID: `io.github.rohanr1904.nearbird`** (permanent once released). Kotlin package names stay `com.bitchat.android` (internal, do-not-touch), as do wire-level identifiers (`bitchat://verify`, BLE UUIDs) for BitChat compatibility (Decision 012).
Consequences:
Deferred items stay tracked in `docs/AI_STATUS.md`. The app ID and name are permanent once published, so they need explicit owner answers before the change is made.

