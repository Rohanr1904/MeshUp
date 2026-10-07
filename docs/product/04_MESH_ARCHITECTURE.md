# 04 — Mesh Architecture

## Architectural rule
Preserve the existing BitChat Android mesh/protocol/security foundation unless a change is demonstrably necessary.

Prefer adapters and incremental improvements over rewrites.

## Target flow

UI
→ ViewModel
→ Domain
→ Message Router
→ Transport Manager
→ BLE / Wi-Fi Aware / optional Internet
→ Protocol
→ Crypto
→ Persistence

## Core components

### Identity
Owns local device identity and user-facing display name.

### Message Router
Responsibilities:
- destination handling
- deduplication
- TTL
- queueing
- retry
- relay decisions
- acknowledgements

### Transport Manager
Selects and coordinates available transports.

Conceptual interface:

```kotlin
interface MeshTransport {
    suspend fun start()
    suspend fun stop()
    suspend fun discover()
    suspend fun connect(peer: PeerHandle)
    suspend fun disconnect(peer: PeerHandle)
    suspend fun send(packet: ByteArray)
}
```

Do not force an interface refactor if the existing architecture already provides a safer abstraction.

### Persistence
Source of truth for:
- peers
- conversations
- rooms
- messages
- outbox
- relay state
- delivery state
- trusted contacts
- preferences

## Message lifecycle

Sender
→ create message
→ persist locally
→ enqueue
→ router
→ transport
→ peer
→ relay if required
→ recipient
→ acknowledgement
→ update delivery state

## Failure model
Support:
- peer disappears
- transport fails
- app backgrounds
- process restarts
- duplicate packet
- out-of-order delivery
- TTL expiry
- fragmented message failure
- temporary route unavailability

## Battery model
Use adaptive behavior:
- ACTIVE
- BALANCED
- LOW_POWER
- PAUSED

Never run uncontrolled scanning, advertising or retry loops.

## Physical-device rule
Radio/network behavior must be validated on real Android devices. Unit tests alone are insufficient for BLE/Wi-Fi behavior.

## Security rule
Do not invent cryptographic primitives. Reuse the established protocol and crypto architecture unless security review proves otherwise.
