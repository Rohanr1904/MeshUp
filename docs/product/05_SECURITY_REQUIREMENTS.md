# 05 — Security Requirements

## Security priorities
1. End-to-end confidentiality
2. Peer authenticity
3. Message integrity
4. Replay resistance
5. Safe local storage
6. Minimal metadata exposure
7. Explicit privacy controls

## Identity
- Display name is not the cryptographic identity.
- Device identity must be generated and stored securely.
- Support identity verification for trusted contacts.

## Encryption
- Reuse established project cryptography.
- Do not invent custom encryption.
- Protect session keys and private keys.
- Fail closed when authentication or decryption fails.

## Local storage
Protect sensitive material using Android-appropriate secure storage mechanisms.
Do not place secrets in plain SharedPreferences or logs.

## Logging
Never log:
- private keys
- session keys
- message plaintext
- authentication secrets
- sensitive tokens
- raw device identifiers when avoidable

## Privacy
- No silent GPS collection.
- Location sharing must be explicit and granular.
- No unnecessary analytics.
- No default cloud backup of private message content.
- Explain message expiration accurately.

## Message expiration
Expiration only controls the local copy and cannot guarantee deletion of copies already received or relayed to other devices.

## Threats to review
- Impersonation
- Replay
- Spam/flooding
- Message amplification
- Sybil behavior
- Malicious relay
- Metadata leakage
- Lost/stolen device
- Debug/log leakage
- Denial of service
- Battery exhaustion attacks

## Security review rule
Any change to protocol, identity, crypto, routing, packet format or trust model requires explicit security review and regression tests.
