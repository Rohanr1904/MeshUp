# 07 — Roadmap

## Phase 0 — Repository intelligence
- Inspect complete BitChat Android repository
- Map architecture
- Understand protocol and transports
- Security review
- Document technical debt
- Confirm licensing constraints

## Phase 1 — Foundation UX
- Modern onboarding
- Local profile
- Username/display name
- Home screen
- Basic navigation

## Phase 2 — Messaging UX
- Nearby people
- Direct chat
- Delivery states
- Reliable retry
- Persistence verification

## Phase 3 — Nearby Rooms
- Create room
- Join room
- Room discovery
- Permissions/privacy
- Expiration
- Retention

## Phase 4 — Mesh experience
- Mesh-aware delivery wording
- Store-and-forward visibility
- Relay status
- Better recovery UX
- Physical-device testing lab

## Phase 5 — Event Mode
- Temporary event
- Channels
- Announcements
- Event join flow
- QR invite

## Phase 6 — Trust & privacy
- QR contact verification
- Privacy dashboard
- Identity controls
- Safer notifications

## Phase 7 — Emergency Mesh
- Emergency room/broadcast
- Optional location
- Acknowledgements
- Strong safeguards against abuse

## Phase 8 — Performance
- Battery optimization
- Scan/advertise tuning
- Queue limits
- Latency improvements
- Large-message performance

## Phase 9 — Production
- Full test suite
- Real device matrix
- Crash/error handling
- Accessibility
- Privacy review
- Security review
- Release signing
- AAB/APK pipeline

## Phase 10 — Growth features
- Organizer tools
- Optional online bridge
- Media
- Moderation
- Community/event analytics with privacy protections

## Release gates
No release until:
- two-device offline chat works
- multi-hop relay works
- persistence survives process/lifecycle disruptions
- security regression tests pass
- battery behavior is acceptable
- permissions are correct
- release APK/AAB installs successfully
