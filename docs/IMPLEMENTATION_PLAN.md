# MeshUp — Implementation Plan: Phase 1 (App shell, identity UX, Internet opt-in)

> Status: **APPROVED by product owner 2026-10-07.** Execute PR-1 → PR-5 in order; each PR reviewed by the chief engineer before the next starts. · Baseline `8d7de5e`
> Implements: TARGET_ARCHITECTURE R-3 (shell), R-2 (domain models for new screens only), R-6 (Internet switch, Decision 013).
> Out of scope: R-1 outbox, R-5 engine fixes (Phase 2), password/invite rooms (Phase 3), Wear features (Decision 014).
> Evidence: Phase 1 inspection 2026-10-07 (android-engineer report; key claims re-verified by the chief engineer).

## 1. Facts the plan relies on (CONFIRMED)
- `MainActivity.kt:315-333`: the `CHECKING/INITIALIZING/COMPLETE` branch hosts `ChatScreen(chatViewModel)`. `ChatViewModel` is built once by a custom factory (`MainActivity.kt:65-72`).
- `navigation-compose` 2.9.8 is already a dependency (`app/build.gradle.kts:143`) and is unused. **No new dependency is needed** for the shell.
- Onboarding is a permission state machine (`onboarding/OnboardingState.kt`). The only first-run flag is `bitchat_permissions/first_time_onboarding_complete`.
- Nickname: `bitchat_prefs/"nickname"`, random `anon####` default (`ui/DataManager.kt:34-48`). The mesh announce re-reads it via `NicknameProvider`. **No length validation anywhere**; `IdentityAnnouncement` rejects >255 UTF-8 bytes.
- Tor: `TorPreferenceManager` (`bitchat_settings/tor_mode`, default `ON`). Nostr relays connect in `NostrBackgroundRuntime.initialize` (called from `BitchatApplication:71`). Geohash/location-notes entry points live in `ChatHeader.kt` / `LocationChannelsSheet.kt`. `MessageRouter` can fall back to `NostrTransport` for mutual favourites.
- Wear compiles an allow-list of shared sources (`wear/build.gradle.kts:88-118`). `ui/` is not shared (except 3 files). A new `meshup/` package is invisible to Wear.
- `AppStateStore` is a process singleton usable without a ViewModel. Peer nicknames, favourites and channel membership exist only on `ChatViewModel`.
- Test infra: Robolectric JVM tests work. `androidTest` deps (compose ui-test) and `testInstrumentationRunner` are declared, but there is no `androidTest` source set yet. AVD `Pixel_7` (API 36) is available locally.

## 2. Design decisions for Phase 1
| # | Decision | Reason | Alternative rejected |
|---|---|---|---|
| P1-1 | All new code lives in `app/src/main/java/com/bitchat/android/meshup/{shell,domain,service,profile,settings,onboarding,people,rooms}`. | Isolated from Wear and from the legacy `ui/`; clean strangler boundary. | Adding to `ui/`: mixes with the 1.7k-line VM's packages. |
| P1-2 | Adapters wrap the **single existing** `ChatViewModel` instance (passed down from `MainActivity`), plus `AppStateStore`. | Two ViewModels would diverge state. | New VM over the mesh directly: duplicates managers. |
| P1-3 | The "Chats" tab hosts the legacy `ChatScreen` unchanged in Phase 1. | Zero regression risk to working chat; replaced incrementally later. | Rewriting chat UI now: large and untested. |
| P1-4 | Display name stays in `bitchat_prefs/"nickname"` (via `DataManager`); a new `ProfileRepository` owns validation: trim, 1–32 chars, ≤255 UTF-8 bytes, no control chars. | Announce path keeps working; wire unchanged (Decision 012). | New key: breaks announce/nickname sync. |
| P1-5 | The name step is shown once, after permissions, gated by new pref `meshup_settings/profile_name_confirmed`. The random `anon####` stays as a pre-filled suggestion. | Permission flag is permission-specific; must not reuse it. | Blocking first launch on name before permissions: worse funnel. |
| P1-6 | `NetworkSettings.internetEnabled` (`meshup_settings/internet_enabled`, **default false**) is the master switch. When false, Tor mode is treated as OFF regardless of `tor_mode`, and Nostr/location-notes init is skipped. | Decision 013. Master switch overrides; `tor_mode` remains the sub-setting when ON. | Deleting Nostr/Tor: irreversible (rejected in R-6). |
| P1-7 | **No migration for existing installs:** MeshUp ships under a new `applicationId` (R-10), so every MeshUp install is new; default OFF applies to all. | Removes the "existing installs" open question. | Preserving ON for upgraders: there are no MeshUp upgraders. |
| P1-8 | Manual DI: one `MeshUpContainer` created in `MainActivity`. No Hilt. | No dependency/lockfile change under STRICT locking. | Hilt: lockfile + verification-metadata churn. |

## 3. Work breakdown (PR-sized, in order)

### PR-1 Foundations (no wiring; zero user-visible change)
- `meshup/profile/DisplayNameValidator.kt`, `ProfileRepository.kt` (delegates to `DataManager`).
- `meshup/settings/NetworkSettings.kt` (prefs + `StateFlow<Boolean>`).
- `meshup/domain/{Person,Conversation,Room}.kt` + mappers from existing flows (R-2; wire models untouched).
- `meshup/service/{MessagingService,PeopleService,RoomService}.kt` interfaces.
- **Tests (JVM):** validator table (empty, whitespace, 32/33 chars, emoji/UTF-8 byte limit, control chars); NetworkSettings default false + persistence; mapper unit tests.
- Owner: android-engineer (Sonnet).

### PR-2 Internet opt-in gate (privacy-critical)
- `BitchatApplication.kt`: read `NetworkSettings` first. When OFF, skip `NostrBackgroundRuntime.initialize`, skip `LocationNotesInitializer.initialize` (also the call at `MainActivity.kt:690`), and init Tor with effective mode OFF (keep `ArtiTorManager.init` so `OkHttpProvider` stays consistent).
- Toggle ON at runtime: start the same components. Toggle OFF: disconnect relays (`AppShutdownCoordinator` path) and stop Tor.
- Hide/disable geohash + location-notes entry points in the legacy UI when OFF (minimal conditional in `ChatHeader.kt` / `LocationChannelsSheet.kt`).
- `MessageRouter` Nostr fallback: when OFF, messages for offline favourites must take the **QUEUED** path, not NOSTR. Implement as an injected predicate; add a unit test. This is engine-adjacent, so keep the diff minimal and route behaviour otherwise unchanged.
- **Tests:** unit tests for the gate and the router predicate. **Emulator cold start with the switch OFF: verify no outbound sockets** (Tor/relay hosts) via logcat + `adb shell` netstat or a network log.
- Owner: android-engineer (Sonnet). **Review: security-engineer (Opus)** plus the chief engineer.

### PR-3 App shell + adapters
- `meshup/shell/MeshUpApp.kt`: `NavHost` with bottom bar **Chats · People · Rooms · Profile**. Chats = legacy `ChatScreen(chatViewModel)`.
- `MainActivity.kt`: in the `COMPLETE` branch replace `ChatScreen(...)` with `MeshUpApp(container)`. This is a one-call-site change.
- Adapters: `ChatViewModelMessagingService`, `ChatViewModelPeopleService` (peers, nicknames, direct/relay, favourites), `ChannelRoomService` (joined channels, unread counts, join/leave via existing `ChannelManager` commands).
- Small screen ViewModels for People and Rooms depend only on the interfaces.
- **Tests:** adapter tests with fake flows; first `androidTest` Compose test: shell renders, tabs navigate, Chats shows the legacy screen. Before writing it, confirm `androidx.test:runner` is on the androidTest classpath; if a dependency/lock change is needed, **stop and ask**.
- Owner: android-engineer (Sonnet); optional ux-product pass on IA/copy.

### PR-4 Display-name onboarding + Profile + Settings screens
- Name step after `INITIALIZING` when `profile_name_confirmed` is false. Copy: "This name is visible to people nearby. You can change it any time."
- Profile screen: edit display name (same validator), show a short fingerprint for verification (existing `peerFingerprints`/identity), no raw IDs.
- Settings: **Internet features** switch with a plain explanation of what turns on (Tor, relays, location channels), plus links to the existing battery/power settings.
- **Tests:** VM unit tests; Compose test for the name step (validation errors, save, does not show again).
- Owner: android-engineer (Sonnet); ux-product for copy.

### PR-5 Rooms tab (public channels only)
- List joined rooms with unread counts; "Join a room" by name (wraps the existing `/join`); leave room.
- **Password rooms are not exposed** (verification stub `ChannelManager.kt:125`; fixed in Phase 3 / R-4).
- **Tests:** RoomService adapter tests; Compose test for join/leave.

## 4. Do-not-touch in Phase 1
`protocol/`, `noise/`, `crypto/`, `identity/`, `mesh/` (beyond nothing), packet formats, DB schema, `gradle/*.lockfile`, `verification-metadata.xml`, CI workflows, Wear-shared files (except where a gate is unavoidable and Wear still compiles), Kotlin package names, existing pref keys.

## 5. Phase 1 exit criteria
1. App launches into permissions → name step → shell; the name step shows once.
2. All four tabs render; Chats behaves exactly as before.
3. Fresh install makes **no Internet connections** until the user enables Internet features (emulator-verified).
4. JVM: all new tests green; the existing suite is no worse than baseline (TD-29 failures unchanged); the 12 characterization tests unchanged.
5. `:app:assembleDebug`, `:wear:assembleDebug`, `lintDebug` pass.
6. Emulator smoke test (Pixel_7, API 36) of onboarding and tab navigation. **Mesh behaviour is not claimed**: it needs 2+ physical devices (Decision 009).

## 6. Risks
| Risk | Mitigation |
|---|---|
| Hidden Internet start paths (GeohashViewModel, LocationChannelManager, NostrTransport fallback) | PR-2 adds an emulator socket check; security review |
| Legacy `ChatScreen` assumes it owns the whole window (top bar, sheets) | PR-3 hosts it full-screen inside the tab; bottom bar inset tested on emulator |
| `ChatViewModel` lifecycle tied to the Activity | Adapters take the existing instance; no second VM |
| OneDrive build locks | Build from a non-synced clone |
| Windows TD-29 noise hides regressions | Compare failing-test set against baseline; confirm on CI |

## 7. Agent/model routing
- PR-1, PR-3, PR-4, PR-5: android-engineer (Sonnet), one PR at a time, single owner per file.
- PR-2: android-engineer (Sonnet) implements; security-engineer (Opus) reviews.
- QA: qa-engineer (Haiku) for full-suite runs and baseline comparison after each PR.
- The chief engineer reviews every diff before it is considered done.
