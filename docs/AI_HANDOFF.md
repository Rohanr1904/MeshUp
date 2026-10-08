# AI Handoff

## From
Chief engineer (main Claude Code session), 2026-10-08. This session covered Phase 1 and Phase 2 (PRs #1–#17) and launch preparation for NearBird.

## To
The next Claude Code session.

## Read first
- `docs/AI_STATUS.md`: current state, launch gates and deferred items.
- `docs/product/08_DECISIONS.md`: Decisions 011–016, including the Decision 015 amendment.
- `docs/IMPLEMENTATION_PLAN.md` (Phase 1) and `docs/IMPLEMENTATION_PLAN_PHASE2.md`.
- The owner preference: **product decisions use the proposed defaults**. Record each one and report it. Still ask about legal questions, irreversible public actions (publishing, making the repo public, shipping the app ID), and anything involving credentials or signing keys.

## Where things are
- **Repository:** `github.com/Rohanr1904/MeshUp`. **Work in `C:\dev\MeshUp`.** `C:\dev\MeshUp-ops` is a second worktree used for merge/sync work. The OneDrive folder is stale; don't build there.
- **App:** NearBird, `io.github.rohanr1904.nearbird`. Kotlin packages remain `com.bitchat.android`, and wire identifiers stay BitChat-compatible (Decision 012).
- **Build:** run Gradle with `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- **Local unit tests:** the 5 TD-29 classes fail on Windows only (see AI_STATUS); CI on Ubuntu is authoritative.
- **Merge flow:** sync the branch with `main`, push, and wait for all 5 CI checks. Merge only when every check is green, using a merge commit.
- **Review pattern:**
  - Engine, crypto and DB PRs get a security-engineer (Opus) review, plus a mesh-architect (Opus) review where relevant.
  - Fix the findings, and re-review whenever the verdict was "changes required".
  - Subagents tend to hit their turn limits; resume them with a prioritised finish list.

## Key architecture added in Phase 1–2
- **Shell and UI:** the `meshup/` package holds the app shell, adapters over the single `ChatViewModel`, and the Profile, Settings and Rooms screens. The legacy `ChatScreen` is hosted in the Chats tab.
- **Internet gate:** `meshup/settings/InternetGate` + `InternetController`. It is OFF by default, and the OkHttp interceptor, relay guards and Tor reconcile are fail-closed.
- **Durable delivery:** `services/MessageRouter` with `OutboxPersistence` → `ConversationRepository` → `OutboxStore`. Rehydrate is owned by `MeshServiceHolder`, and a generation counter coordinates with panic. Admission is paused during panic.
- **Engine hardening:**
  - replay-window fix
  - TTL clamp in `PacketRelayManager`
  - striped bounded `PacketProcessor` with rate limits
  - fail-closed signing (`mesh/PacketSigning.kt`)
  - identity-reset detection (`identity/IdentityHealth`)
  - release log stripping plus HMAC `util/Redact`
- **APK trust:** `isTrustedApkSigner` in `util/UniversalApkManager.kt` accepts only the pinned cert or the app's own current signer. The pin is blank until the owner creates a key.

## Unresolved / owner actions
- Signing key and certificate pin; GPLv3 vs business model; trademark/domain for "NearBird".
- The physical-device test session (launch gate).
- TD-29: approval to shorten the test temp dir in the build file, or to enable Windows long paths.

## Recommended next step
1. Merge P2-PR9 after its reviews.
2. Then either Phase 3 planning (only if password rooms are wanted in v1), or launch prep: a licences screen and a release-pipeline dry run with the owner's key.
