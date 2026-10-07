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
