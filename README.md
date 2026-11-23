# DriveLive — In-Car Multimodal AI Co-Pilot ("KAI")

**DriveLive** is a Kotlin Multiplatform (KMP) and Compose Multiplatform in-car driving co-pilot for **iOS** and **Android**. Powered by the **Gemini Live Bidirectional WebSocket API (`BidiGenerateContent`)**, DriveLive gives drivers a hands-free, low-latency voice and vision assistant named **KAI** (*Knight Automotive Intelligence*, inspired by KITT from *Knight Rider*)—designed with a high-contrast OLED car HUD, hardware acoustic echo cancellation, on-demand road/dashboard camera inspection, and native navigation & media integrations.

---

## Key Features

* **Real-Time Bidirectional Voice (`16kHz` In / `24kHz` Out):**
  * Full-duplex PCM audio streaming with instant **barge-in interruption** handling (immediately drains and flushes the playback buffer when the driver speaks over KAI).
  * **Hardware Acoustic Echo Cancellation (AEC) & Bluetooth Car Routing:** Uses `VOICE_COMMUNICATION` + `AcousticEchoCanceler` on Android and `AVAudioSessionModeVoiceChat` + voice processing on iOS so vehicle speakers never re-trigger the microphone.
  * Multilingual conversational fluency across **English**, **Tagalog**, and **Taglish**.
* **On-Demand Vision Bursts (Thermal & Battery Safe):**
  * The device camera stays **OFF** by default to prevent overheating on dashboard mounts.
  * Triggered by voice (*"KAI, read that road sign"* / *"What is this warning light?"*) or a single-tap HUD control, activating an **8-second, 1 FPS (`768x768` JPEG)** camera burst with a live picture-in-picture viewfinder overlay and countdown pill before automatically shutting down.
* **Non-Blocking Native Tool Execution:**
  * `navigate_to`: Launches turn-by-turn directions in **Waze**, **Google Maps**, or **Apple Maps** grounded with live GPS coordinates.
  * `play_music`: Deep-links tracks, artists, and driving playlists directly into **Spotify**.
  * `inspect_road_view`: Triggers the on-demand camera burst to visually inspect traffic, signage, or dashboard indicators.
* **Resilient Commute Sessions:**
  * Built-in **Session Resumption** (`sessionResumption` handles) with automatic exponential backoff reconnection across cellular dead zones and tower handoffs, plus sliding-window context compression (`contextWindowCompression`) for long drives.
* **Glanceable OLED Car HUD:**
  * True `#050505` OLED black interface with minimum `72.dp` touch targets, at most 3 primary controls on screen, and a Knight Rider-inspired crimson LED voice amplitude modulator synced to KAI's speech.

---

## Architecture & Tech Stack

| Layer | Technology |
| :--- | :--- |
| **Shared UI** | Compose Multiplatform (Material 3, OLED Dark Car HUD in `commonMain`) |
| **State Management** | Formal Finite State Machine ([`MichaelStateMachine`](./shared/src/commonMain/kotlin/com/nowlsantos/drivelive/state/MichaelStateMachine.kt) & [`MichaelSessionState`](./shared/src/commonMain/kotlin/com/nowlsantos/drivelive/state/MichaelSessionState.kt)) + [`DriveLiveViewModel`](./shared/src/commonMain/kotlin/com/nowlsantos/drivelive/presentation/DriveLiveViewModel.kt) |
| **Live Networking** | Ktor WebSockets (`OkHttp` on Android, `Darwin` on iOS) + `kotlinx.serialization` ([`MichaelLiveClient`](./shared/src/commonMain/kotlin/com/nowlsantos/drivelive/network/MichaelLiveClient.kt)) |
| **Dependency Injection** | Koin Multiplatform (`koin-core`, `koin-compose`, `koin-compose-viewmodel`) |
| **Android Hardware Bridges** | `AudioRecord` / `AudioTrack` (AEC + Bluetooth SCO/BLE), `CameraX` (`ImageAnalysis` + `PreviewView`), `LocationManager`, Native Intents |
| **iOS Hardware Bridges** | `AVAudioEngine` / `AVAudioPlayerNode` (VoiceChat AEC + 48kHz-to-16kHz downsampler), `AVFoundation` (`UIKitView` preview layer), `CLLocationManager`, `UIApplication` URL schemes |

### Project Structure

```text
DriveLive/
├── shared/                          # Kotlin Multiplatform shared module
│   └── src/
│       ├── commonMain/kotlin/       # Shared Compose HUD, FSM, Gemini Live WebSocket client, Tools, Koin DI
│       ├── androidMain/kotlin/      # Android actual bridges (AudioTrack/Record, CameraX, Maps/Spotify Intents)
│       └── iosMain/kotlin/          # iOS actual bridges (AVAudioEngine, AVFoundation, CoreLocation, MainViewController)
├── androidApp/                      # Android application entry point (:androidApp)
├── iosApp/                          # iOS SwiftUI host project & Xcode workspace (iosApp.xcodeproj)
├── PROJECT_SPEC.md                  # Full technical & hardware specification
└── AGENT_SPEC.md                    # KAI persona, voice config, and tool schemas
```

---

## Getting Started

### 1. Configure Your Gemini API Key

Create a `local.properties` file in the root directory (ignored by Git):

```properties
GEMINI_API_KEY=your_gemini_api_key_here
```

For **iOS builds**, also set `GEMINI_API_KEY` in [`iosApp/Configuration/Config.xcconfig`](./iosApp/Configuration/Config.xcconfig):

```ini
GEMINI_API_KEY=your_gemini_api_key_here
```

---

### 2. Running on Android (Windows, macOS, or Linux)

1. Open the project root in **Android Studio**.
2. Select the **`androidApp`** run configuration and target an Android device or emulator.
3. Click **Run (▶)**, or build from the terminal:
   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

---

### 3. Running on iPhone / iOS (macOS Required)

You can run the iOS app either directly from **Android Studio on a Mac** or via **Xcode**:

#### Option A: Run from Android Studio on macOS
1. Ensure **Xcode** is installed on your Mac and opened at least once (`xcode-select --install`).
2. In Android Studio on your Mac, install the **Kotlin Multiplatform** plugin (**Settings -> Plugins -> Marketplace**).
3. Open the `DriveLive` repository in Android Studio.
4. Select the **`iosApp`** run configuration in the top toolbar, choose an **iPhone Simulator** (or physical iPhone), and click **Run (▶)**.

#### Option B: Run from Xcode on macOS
1. Open `iosApp/iosApp.xcodeproj` in **Xcode**.
2. If deploying to a physical iPhone, go to **Signing & Capabilities** and select your Apple Developer **Team** (or set `TEAM_ID` in [`iosApp/Configuration/Config.xcconfig`](./iosApp/Configuration/Config.xcconfig)).
3. Select your target iPhone Simulator or physical iPhone and press **Cmd + R**.
   *(Xcode's build phase automatically invokes `./gradlew :shared:embedAndSignAppleFrameworkForXcode` to compile `Shared.framework`).*

---

## CI / Automated iOS Builds

A GitHub Actions workflow is configured in [`.github/workflows/build-ios.yml`](./.github/workflows/build-ios.yml) to automatically compile and verify the Kotlin/Native iOS frameworks (`:shared:linkDebugFrameworkIosSimulatorArm64` and `:shared:linkDebugFrameworkIosArm64`) on `macos-15` runners on every push or pull request.
