<p align="center"><img src="docs/brand/nearbird-icon.svg" alt="NearBird icon: a small bird with signal arcs" width="160"/></p>

# NearBird

NearBird is an offline-first Android messenger. Phones near each other exchange messages over a Bluetooth mesh, with no account, no phone number and no central servers. Private chats are end-to-end encrypted. Optional Internet features exist but are **off by default** and must be turned on by the user in Settings.

[GitHub Releases](https://github.com/Rohanr1904/nearbird/releases)

## Features

- **Offline Bluetooth LE mesh**: automatic peer discovery and multi-hop relay (max 7 hops), no Internet needed
- **End-to-end encrypted private chats**: [Noise Protocol](https://noiseprotocol.org) (XX pattern, X25519 + ChaCha20-Poly1305)
- **No account**: no phone number, no email, no NearBird servers
- **Delivery retry**: unsent private messages wait in an encrypted on-device outbox and are retried
- **Encrypted conversation storage** on the device
- **Channel chats**: topic-based group messaging with optional password protection (PBKDF2-SHA256 key derivation + AES-256-GCM)
- **IRC-style commands**: `/join`, `/msg`, `/who`
- **Wi-Fi Aware transport**: higher-bandwidth local mesh on supported devices
- **Emergency wipe**: triple-tap to clear all data
- **Opt-in Internet features** (off by default): location-based channels over Nostr relays, with optional Tor (Arti)
- **Wear OS companion app**

Compatibility: NearBird v1 is wire-compatible with BitChat clients (Decision 012 in [docs/product/08_DECISIONS.md](docs/product/08_DECISIONS.md)), so it can exchange mesh messages with them.

## Technical Architecture

### Bluetooth Mesh Network (Offline)

- Direct peer-to-peer within Bluetooth range, multi-hop relay through nearby devices
- Noise Protocol sessions with forward secrecy; peer identities derived from static keys
- Compact binary packet format with fragmentation, TTL routing, and deduplication
- Adaptive duty cycling and connection limits for battery efficiency
- Foreground service keeps the mesh alive within Android background execution limits

### Nostr Protocol (Internet, opt-in)

- Global reach via public relays, geohash-based location channels
- Private messages fall back to Nostr for mutual favorites when the mesh is unavailable
- Ephemeral keys per geohash area

### Android Stack

- Kotlin, Jetpack Compose (Material 3), MVVM
- Coroutines and Flow for all networking and state
- Core components: `MeshForegroundService` (persistent connectivity), `BluetoothMeshService` / `WifiAwareMeshService` (transports), `UnifiedMeshService` (transport selection), `NoiseSessionManager` (encryption sessions), `MessageRouter` (mesh/Nostr routing with outbox retry)

## Building

Requires Android Studio and the Android SDK (API 26+).

```bash
git clone https://github.com/Rohanr1904/nearbird.git
cd MeshUp
./gradlew assembleDebug
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app requests Bluetooth, location (required for BLE scanning), and notification permissions at runtime.

Release APKs can be rebuilt in a pinned Linux container; see below.

## Testing

```bash
# Unit tests
./gradlew test

# Lint
./gradlew lint

# Instrumented tests (requires a device or emulator)
./gradlew connectedAndroidTest
```

Note that BLE mesh behavior is difficult to emulate; protocol and session logic is covered by unit tests, while radio-level behavior needs real devices.

## Reproducible builds and verification

See [Reproducible builds](docs/reproducible-builds.md) for the build trust model and the procedure to verify a release.

## Privacy

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) (draft, pending review). The developers receive no data from the app.

## License

This project is licensed under the **GNU General Public License v3.0**. See the [LICENSE](LICENSE.md) file for the full text.

Under GPLv3, anyone who receives the app may obtain, modify and redistribute its corresponding source code under the same licence.

NearBird is a modified version of [bitchat for Android](https://github.com/permissionlesstech/bitchat-android) by permissionlesstech and its contributors, also licensed under GPLv3; see [NOTICE](NOTICE) for attribution and modification notices. The NearBird name and logo are covered by [TRADEMARKS.md](TRADEMARKS.md); the code is not restricted by it.
