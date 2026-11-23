# PROJECT_SPEC.md — DriveLive

## 1. Project Overview
* **App Name:** DriveLive
* **Package / Bundle ID:** `com.nowlsantos.drivelive`
* **In-App AI Co-Pilot:** **KAI** (Knight Automotive Intelligence — Single-Orchestrator Multimodal Assistant)
* **Primary Target:** Android (Windows PC + Android Studio development)
* **Secondary Target:** iOS (`iosArm64`, `iosSimulatorArm64` compiled via GitHub Actions macOS runner)

## 2. Technology Stack & Architectural Rules
* **UI Framework:** Compose Multiplatform (Shared UI in `commonMain`, Material Design 3, High-Contrast OLED Dark Car Theme).
* **Language & Concurrency:** 100% Kotlin, Coroutines, `StateFlow` / `SharedFlow` (Zero Python).
* **Networking & Serialization:** Ktor Client (`OkHttp` engine on Android, `Darwin` engine on iOS) with WebSockets (`BidiGenerateContent`) + `kotlinx.serialization`.
* **Dependency Injection:** Koin Multiplatform (`koin-core`, `koin-compose`, `koin-compose-viewmodel`).
* **Local Persistence (Phase 3):** Room Multiplatform (KMP) for voice memos, tasks, and scanned receipt logs.
* **Architecture Pattern:** Formal Finite State Machine (`KaiSessionState`) driving a unidirectional Compose HUD, paired with `expect/actual` platform hardware bridges.

## 3. Core Functional & Hardware Constraints
1. **Audio-First Loop with Hardware AEC:**
    * Default session mode is bidirectional audio (`16kHz` mono 16-bit PCM input, `24kHz` mono 16-bit PCM output).
    * Must use hardware Acoustic Echo Cancellation (`VOICE_COMMUNICATION` on Android; `AVAudioSessionModeVoiceChat` on iOS) so car speakers do not cause self-interruption.
    * Immediate audio playback flush upon receiving `serverContent.interrupted = true` (barge-in).
2. **On-Demand Vision (Thermal & Battery Protection):**
    * Uses the mounted device camera—not an external Wi-Fi dashcam—to preserve cellular internet and zero-latency frame access.
    * Camera stays **OFF** by default. Activates only on explicit voice trigger or single-tap button for an **8-second burst** (`1 FPS` JPEG, max `768x768`), then shuts down automatically.
3. **Resilient Live Session:**
    * Enable `contextWindowCompression` (sliding window) and `sessionResumption` handles in the Gemini Live setup payload to survive cellular tower handoffs and long commutes.
4. **Glanceable Car UX Rules:**
    * Minimum interactive touch target: `72.dp`.
    * Maximum 3 primary interactive controls on screen while driving.
    * High-contrast OLED black (`#050505`) background with Knight Rider-inspired segmented LED voice amplitude visualizer synced to KAI's speech.

## 4. Platform Bridge Contracts (`commonMain` `expect` / `actual`)
* **`AudioEngine`**: Captures `16kHz` PCM mic stream + amplitude; plays `24kHz` PCM audio; flushes buffer on barge-in.
* **`CameraFrameStreamer`**: Emits `Flow<ByteArray>` (`1 FPS` JPEG frames) for bounded duration bursts.
* **`NavigationLauncher`**: Fires native intents/URL schemes for Google Maps (`google.navigation:` / `comgooglemaps://`), Waze (`https://waze.com/ul`), or Apple Maps (`http://maps.apple.com/`).
* **`MediaLauncher`**: Launches Spotify URIs (`spotify:track:...`, `spotify:playlist:...`, `spotify:search:...`) or triggers Spotify Web API playback.
* **`LocationProvider`**: Returns last-known `(latitude, longitude)` to ground local navigation and search queries.
* **`DisplayController`**: Keeps screen awake (`FLAG_KEEP_SCREEN_ON` / `isIdleTimerDisabled = true`) during active driving sessions.

## 5. Phased Delivery Roadmap
* **Phase 0 (Current):** Android Studio KMP project initialization + `PROJECT_SPEC.md` and `AGENT_SPEC.md` committed to repo root.
* **Phase 1 (Core Voice Loop):** Gradle dependencies, `KaiSessionState` FSM, `KaiLiveClient` Ktor WebSocket client, Android `AudioEngine` (AEC), and DriveLive Car HUD.
* **Phase 2 (Tools & Vision):** Non-blocking tool dispatcher, `NavigationLauncher` (Google Maps & Waze), `MediaLauncher` (Spotify PKCE + URI fallback), and on-demand `CameraFrameStreamer` (CameraX).
* **Phase 3 (Commute Memory):** Room KMP persistence for voice notes/receipts and hands-free messaging intents.
* **Phase 4 (iOS Port & CI):** `iosMain` `actual` implementations (`AVAudioEngine`, `AVFoundation`, `CoreLocation`, URL schemes) + GitHub Actions macOS framework build verification.