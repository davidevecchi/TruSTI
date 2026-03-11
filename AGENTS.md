# Agent Rules for TruSTI

## Project Overview

TruSTI is an Android app (Kotlin, minSdk 26, targetSdk/compileSdk 36) for private, end-to-end encrypted messaging using QR-code identity bootstrapping and WebRTC P2P transport. No servers, no accounts.

## Build

- AGP 9.1.0, Kotlin 2.2.10, Gradle 8.11+
- Build: `./gradlew assembleDebug`
- Check for errors: `./gradlew compileDebugKotlin`
- Run lint: `./gradlew lintDebug`
- `kotlin { jvmToolchain(11) }` is a **top-level block** in `app/build.gradle.kts`, outside `android {}`.
- `android.useAndroidX=true` must be present in `gradle.properties`.

## Architecture

Four UI layers managed by `MainActivity` (fragment host with bottom nav):
- `HomeFragment` — own QR display + scanner launch
- `ContactsFragment` — contact list → `ConversationActivity`
- `TestsFragment` — medical test records (STI screening history)
- `SettingsFragment` — profile + theme

P2P stack: `P2PMessenger` → `WebRtcTransport` + `TorrentSignaling` + `Encryption`

Storage: `ContactStore`, `MessageStore`, `TestsStore` all use SharedPreferences with JSON serialization.

## Code Conventions

- Prefer Kotlin idiomatic style: data classes, extension functions, coroutines over callbacks.
- Use `viewBinding` (already enabled) — never use `findViewById`.
- Fragments null their binding in `onDestroyView`: always use `_binding`/`binding` pattern.
- `registerForActivityResult` launchers must be declared as class-level properties (before `onStart`).
- `P2PMessenger` is a singleton accessed via `P2PMessenger.get(context)`.
- All crypto operations go through `smp/Encryption.kt` — do not inline crypto logic.
- Room IDs and handshake logic live in `TorrentSignaling.kt` — do not duplicate in UI code.

## Compose vs Views

The project mixes View-based fragments and Compose activities:
- Fragments use XML layouts + ViewBinding.
- `AddRecordActivity` uses Compose (`setContent {}`), extends `ComponentActivity`.
- Do not mix Compose into existing View-based fragments without discussion.

## Key Files

| File | Purpose |
|------|---------|
| `crypto/KeyManager.kt` | EC P-256 key pair, SharedPreferences storage |
| `connection/QrHelper.kt` | QR gen + `trusti://peer?pk=` URI parsing |
| `smp/Encryption.kt` | ECDH-ephemeral + AES-256-GCM |
| `smp/TorrentSignaling.kt` | WebTorrent tracker WS signaling |
| `smp/WebRtcTransport.kt` | RTCPeerConnection + DataChannel per contact |
| `smp/P2PMessenger.kt` | Singleton; `initialize()`, `sendMessage()`, `messageFlow` |
| `utils/ContactStore.kt` | JSON contacts persistence |
| `utils/MessageStore.kt` | Per-contact message persistence |
| `utils/TestsStore.kt` | JSON test records persistence |
| `utils/ProfileManager.kt` | Username + adjective-noun disambiguation |

## Privacy & Security Rules

- **Never log message content, public keys, or room IDs** at any log level.
- Do not add any network calls beyond the existing WebTorrent tracker and STUN servers.
- Do not store sensitive data in cleartext outside of the existing SharedPreferences stores (which are app-private).
- All message payloads must be encrypted via `Encryption.kt` before sending.

## What to Avoid

- Do not add new server dependencies or change the signaling transport without explicit discussion.
- Do not use `AsyncTask` — use coroutines.
- Do not use `!!` outside of the `binding` getter pattern — use safe calls or `requireNotNull` with a message.
- Do not change `minSdk` below 26 (required for `java.security` EC key APIs used by `KeyManager`).
