# 02 — Product Requirements

## MVP requirements

### Identity
- Create a local display name during onboarding.
- Separate mutable display name from cryptographic device identity.
- Persist identity securely.
- Allow display-name changes.

### Nearby discovery
- Discover nearby peers using supported transports.
- Display friendly peer status without exposing sensitive identifiers.
- Show approximate connectivity such as Direct / Nearby / Relay.

### Direct chat
- One-to-one text messaging.
- Local persistence.
- Sending, queued, relaying, delivered and failed states.
- Retry after temporary failure.
- No Internet required for core messaging.

### Rooms
- Create nearby room.
- Join room.
- Public nearby / invite-only / password-protected modes.
- Optional expiration.
- Message retention setting.
- Local-first operation.

### Mesh
- Multi-hop relay.
- TTL.
- Deduplication.
- Fragmentation and reassembly where already supported.
- Persistent outbox.
- Store-and-forward.
- Delivery acknowledgement.

### Privacy
- No unnecessary account requirement.
- No silent location sharing.
- No plaintext private keys or messages in logs.
- Explicit controls for receipts, visibility, retention and Internet transport.

### Reliability
- Messages survive temporary peer disconnects.
- App lifecycle changes must not silently discard queued messages.
- Database is the source of truth.

### Diagnostics
- Advanced screen for mesh health.
- Hide raw identifiers by default.
- Safe diagnostics suitable for bug reports.

## Future requirements
- Event Mode
- Emergency Mesh
- Contact verification
- Rich media
- Optional Internet bridge
- Better group moderation
- Mesh analytics for organizers without exposing personal data

## Acceptance principle
Every feature must work correctly under intermittent or absent Internet connectivity.
