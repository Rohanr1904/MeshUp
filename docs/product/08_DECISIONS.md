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
