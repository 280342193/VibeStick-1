# VibeStick Android Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Build a native Android app that discovers the existing VibeStick Windows bridge on the LAN, monitors Codex task alerts, sends phone recordings through the computer-side SiliconFlow ASR path, and submits manual phone text to the focused desktop input with Enter.

**Architecture:** Kotlin and Jetpack Compose own the Android UI, microphone capture, UDP discovery, HTTP client, and foreground monitor. The existing Windows bridge remains responsible for Codex state, SiliconFlow ASR, focused-window paste, and Enter injection.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17, Android SDK 35, Jetpack Compose Material 3, Kotlin coroutines, HttpURLConnection, DatagramSocket, AudioRecord, JUnit 4, Compose UI tests.

---

## File Map

Create these project files:

- settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
- gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.properties, gradle/wrapper/gradle-wrapper.jar
- app/build.gradle.kts, app/proguard-rules.pro, app/src/main/AndroidManifest.xml
- app/src/main/java/com/vibestick/android/VibeStickApplication.kt
- app/src/main/java/com/vibestick/android/MainActivity.kt
- app/src/main/java/com/vibestick/android/data/BridgeModels.kt
- app/src/main/java/com/vibestick/android/data/BridgeProtocol.kt
- app/src/main/java/com/vibestick/android/data/BridgeDiscovery.kt
- app/src/main/java/com/vibestick/android/data/BridgeHttpClient.kt
- app/src/main/java/com/vibestick/android/data/BridgeRepository.kt
- app/src/main/java/com/vibestick/android/data/ConnectionStore.kt
- app/src/main/java/com/vibestick/android/audio/PcmRmsCalculator.kt
- app/src/main/java/com/vibestick/android/audio/AudioRecorder.kt
- app/src/main/java/com/vibestick/android/service/NotificationFactory.kt
- app/src/main/java/com/vibestick/android/service/BridgeMonitorService.kt
- app/src/main/java/com/vibestick/android/ui/UiState.kt
- app/src/main/java/com/vibestick/android/ui/MainViewModel.kt
- app/src/main/java/com/vibestick/android/ui/MainScreen.kt
- app/src/main/java/com/vibestick/android/ui/ConnectionSheet.kt
- app/src/main/java/com/vibestick/android/ui/VibeStickTheme.kt
- app/src/main/res/values/strings.xml, colors.xml, themes.xml
- README.md

Create focused tests:

- app/src/test/java/com/vibestick/android/data/BridgeModelsTest.kt
- app/src/test/java/com/vibestick/android/data/BridgeProtocolTest.kt
- app/src/test/java/com/vibestick/android/data/BridgeDiscoveryTest.kt
- app/src/test/java/com/vibestick/android/data/ManualSendCoordinatorTest.kt
- app/src/test/java/com/vibestick/android/data/AlertDeduplicatorTest.kt
- app/src/test/java/com/vibestick/android/audio/PcmRmsCalculatorTest.kt
- app/src/test/java/com/vibestick/android/ui/MainViewModelTest.kt
- app/src/androidTest/java/com/vibestick/android/MainScreenTest.kt

---

### Task 1: Bootstrap The Android Project

**Files:**
- Create: settings.gradle.kts
- Create: build.gradle.kts
- Create: gradle.properties
- Create: gradle/libs.versions.toml
- Create: app/build.gradle.kts
- Create: app/proguard-rules.pro
- Create: app/src/main/AndroidManifest.xml
- Create: app/src/main/res/values/strings.xml
- Create: app/src/main/res/values/colors.xml
- Create: app/src/main/res/values/themes.xml
- Create: README.md

- [x] **Step 1: Add Gradle settings and fixed versions**

Use AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, activity-compose 1.10.0,
lifecycle 2.8.7, and coroutines 1.9.0. Configure google(), mavenCentral(), and
gradlePluginPortal() repositories. Include only the app module.

The app module must use namespace com.vibestick.android, compileSdk 35, minSdk 26,
targetSdk 35, Java/Kotlin target 17, Compose enabled, and the Kotlin Compose plugin.

- [x] **Step 2: Add the manifest contract**

Declare INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE,
CHANGE_WIFI_MULTICAST_STATE, RECORD_AUDIO, POST_NOTIFICATIONS,
FOREGROUND_SERVICE, and FOREGROUND_SERVICE_DATA_SYNC. Set
android:usesCleartextTraffic to true for the private-LAN HTTP bridge. Register
MainActivity and a non-exported BridgeMonitorService with foregroundServiceType
dataSync.

- [x] **Step 3: Generate the Gradle 8.9 wrapper**

Run:

    $gradleZip = Join-Path $env:TEMP 'vibestick-gradle-8.9-bin.zip'
    Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile $gradleZip
    $gradleRoot = Join-Path $env:TEMP 'vibestick-gradle-8.9'
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $gradleRoot -Force
    & (Join-Path $gradleRoot 'gradle-8.9\bin\gradle.bat') wrapper --gradle-version 8.9
    .\gradlew.bat --version

Expected: Gradle 8.9 and JVM 17 are reported.

- [x] **Step 4: Add a minimal launcher activity and build**

Create a temporary MainActivity that renders the app name, then run:

    .\gradlew.bat :app:assembleDebug

Expected: app/build/outputs/apk/debug/app-debug.apk exists.

- [x] **Step 5: Commit**

    git add settings.gradle.kts build.gradle.kts gradle.properties gradle app README.md
    git commit -m "build: bootstrap VibeStick Android app"

### Task 2: Define Protocol Models And Payload Builders

**Files:**
- Create: app/src/main/java/com/vibestick/android/data/BridgeModels.kt
- Create: app/src/main/java/com/vibestick/android/data/BridgeProtocol.kt
- Test: app/src/test/java/com/vibestick/android/data/BridgeModelsTest.kt
- Test: app/src/test/java/com/vibestick/android/data/BridgeProtocolTest.kt

- [x] **Step 1: Write failing state parser tests**

The first test must parse the real state shape:

    @Test
    fun stateParserPreservesAlertAndProvider() {
        val state = BridgeState.fromJson(JSONObject(
            """{"computer_name":"Desk PC","active_provider":"codex",
                "codex":{"status":"DONE"},
                "alert":{"event_id":"done-42","type":"DONE","message":"finished"}}"""
        ))
        assertEquals("Desk PC", state.computerName)
        assertEquals(AgentStatus.DONE, state.codexStatus)
        assertEquals(Alert("done-42", AlertType.DONE, "finished"), state.alert)
    }

Add tests for missing fields, unknown statuses, NONE alerts, and null quota values.

- [x] **Step 2: Write failing protocol tests**

Assert that discoveryRequest emits type vibestick_discover, device VibeStick, and the
supplied token. Assert discovery port 8766, default HTTP port 8765, raw audio endpoint
format, and JSON escaping for manual user text.

- [x] **Step 3: Run tests and verify failure**

    .\gradlew.bat :app:testDebugUnitTest --tests '*BridgeModelsTest' --tests '*BridgeProtocolTest'

Expected: compilation failure because BridgeState and BridgeProtocol do not exist.

- [x] **Step 4: Implement immutable models and structured JSON parsing**

Define AlertType, AgentStatus, Alert, BridgeState, BridgeCandidate, RecordingResult,
ConnectionState, and BridgeFailure. Use JSONObject for every payload and parser;
never concatenate user text into JSON.

BridgeProtocol must expose:

    const val discoveryPort = 8766
    const val defaultHttpPort = 8765
    fun discoveryRequest(token: String): ByteArray
    fun startRecordingBody(sessionId: String): ByteArray
    fun stopVoiceBody(): ByteArray
    fun stopTextBody(text: String): ByteArray
    fun enterEventBody(): ByteArray

- [x] **Step 5: Run tests and commit**

    .\gradlew.bat :app:testDebugUnitTest --tests '*BridgeModelsTest' --tests '*BridgeProtocolTest'
    git add app/src/main/java/com/vibestick/android/data app/src/test/java/com/vibestick/android/data
    git commit -m "feat: add VibeStick protocol models"

Expected: all protocol tests pass.

### Task 3: Implement UDP Discovery And HTTP Transport

**Files:**
- Create: app/src/main/java/com/vibestick/android/data/BridgeDiscovery.kt
- Create: app/src/main/java/com/vibestick/android/data/BridgeHttpClient.kt
- Create: app/src/main/java/com/vibestick/android/data/ConnectionStore.kt
- Test: app/src/test/java/com/vibestick/android/data/BridgeDiscoveryTest.kt

- [x] **Step 1: Write failing discovery parser tests**

    @Test
    fun responseUsesSenderIpAndAdvertisedPort() {
        val candidate = parseDiscoveryResponse(
            "192.168.1.20",
            """{"type":"vibestick_bridge","name":"Desk PC","port":8765,"version":"0.1.5"}"""
        )
        assertEquals(
            BridgeCandidate("192.168.1.20", 8765, "Desk PC", "0.1.5"),
            candidate
        )
    }

Add tests that reject invalid types and default a missing port to 8765.

- [x] **Step 2: Run the focused tests and verify failure**

    .\gradlew.bat :app:testDebugUnitTest --tests '*BridgeDiscoveryTest'

Expected: missing discovery parser compilation failure.

- [x] **Step 3: Implement StickS3-compatible discovery**

BridgeDiscovery.discover must open a DatagramSocket, enable broadcast, use a 1,400 ms
receive window, send to 255.255.255.255:8766 and the last known host, accept only
vibestick_bridge responses, use DatagramPacket.address.hostAddress as host, deduplicate
by host and port, sort by name then host, and close the socket in finally. Run all
socket work on Dispatchers.IO.

- [x] **Step 4: Implement the bridge HTTP client**

Use HttpURLConnection with a 3-second connect timeout and 30-second read timeout.
Implement getState, startRecording, uploadPcm, stopRecording, and postEvent. Protected
requests include X-Vibe-Stick-Token when nonblank. Audio includes sample rate 16000,
channels 1, and bits per sample 16 headers. Decode non-2xx JSON errors into
BridgeFailure.Http.

- [x] **Step 5: Persist the selected bridge and token**

ConnectionStore uses SharedPreferences for host, port, computer name, bridge version,
optional token, and last delivered alert event ID. Never log the token.

- [x] **Step 6: Run tests and commit**

    .\gradlew.bat :app:testDebugUnitTest
    git add app/src/main/java/com/vibestick/android/data app/src/test/java/com/vibestick/android/data
    git commit -m "feat: discover and connect to LAN bridge"

### Task 4: Capture Bounded PCM And Drive Amplitude

**Files:**
- Create: app/src/main/java/com/vibestick/android/audio/PcmRmsCalculator.kt
- Create: app/src/main/java/com/vibestick/android/audio/AudioRecorder.kt
- Test: app/src/test/java/com/vibestick/android/audio/PcmRmsCalculatorTest.kt

- [x] **Step 1: Write failing RMS tests**

    @Test
    fun silenceHasZeroRms() {
        assertEquals(0f, PcmRmsCalculator.rms(ByteArray(320)), 0.001f)
    }

    @Test
    fun louderSamplesProduceHigherRms() {
        val quiet = PcmRmsCalculator.fromSamples(shortArrayOf(500, -500))
        val loud = PcmRmsCalculator.fromSamples(shortArrayOf(12000, -12000))
        assertTrue(loud > quiet)
    }

- [x] **Step 2: Run the test and verify failure**

    .\gradlew.bat :app:testDebugUnitTest --tests '*PcmRmsCalculatorTest'

Expected: PcmRmsCalculator is unresolved.

- [x] **Step 3: Implement little-endian PCM RMS**

Calculate square-mean-root over signed 16-bit little-endian samples and normalize to
0f through 1f. Ignore an unmatched final byte.

- [x] **Step 4: Implement AudioRecord lifecycle**

Use 16 kHz, CHANNEL_IN_MONO, ENCODING_PCM_16BIT and at least the platform minimum
buffer size. Read on Dispatchers.IO, cap at 1,920,000 bytes or 60 seconds, and emit
amplitude at most 20 times per second. Return explicit failures for permission denial,
initialization failure, empty audio, and recordings shorter than 0.7 seconds.

Expose:

    interface PhoneAudioRecorder {
        suspend fun start(onAmplitude: (Float) -> Unit): Result<Unit>
        suspend fun stop(): Result<ByteArray>
        fun cancel()
    }

- [x] **Step 5: Run tests and commit**

    .\gradlew.bat :app:testDebugUnitTest --tests '*PcmRmsCalculatorTest'
    git add app/src/main/java/com/vibestick/android/audio app/src/test/java/com/vibestick/android/audio
    git commit -m "feat: capture phone PCM audio"

### Task 5: Enforce Voice And Manual Send Semantics

**Files:**
- Create: app/src/main/java/com/vibestick/android/data/BridgeRepository.kt
- Test: app/src/test/java/com/vibestick/android/data/ManualSendCoordinatorTest.kt

- [x] **Step 1: Write failing call-order tests**

    @Test
    fun manualSendPastesThenPressesEnter() = runTest {
        val fake = FakeBridgeOperations()
        val result = SendCoordinator(fake).sendText("run tests")
        assertTrue(result.isSuccess)
        assertEquals(listOf("start", "stop:text=run tests", "enter"), fake.calls)
    }

    @Test
    fun voiceUploadNeverPressesEnter() = runTest {
        val fake = FakeBridgeOperations()
        SendCoordinator(fake).sendVoice(byteArrayOf(1, 2))
        assertEquals(listOf("start", "upload", "stop:voice"), fake.calls)
        assertFalse(fake.enterCalled)
    }

Add failure tests for blank manual text, upload failure, paste failure, Enter failure,
and duplicate concurrent sends.

- [x] **Step 2: Run tests and verify failure**

    .\gradlew.bat :app:testDebugUnitTest --tests '*ManualSendCoordinatorTest'

Expected: SendCoordinator and BridgeOperations are unresolved.

- [x] **Step 3: Implement the operation boundary**

    interface BridgeOperations {
        suspend fun startRecording(sessionId: String): RecordingResult
        suspend fun uploadPcm(sessionId: String, pcm: ByteArray): RecordingResult
        suspend fun stopRecording(text: String?, paste: Boolean): RecordingResult
        suspend fun pressEnter(): RecordingResult
    }

Voice starts a sticks3_pcm session, uploads PCM, and stops with paste true. It never
calls pressEnter. Manual text starts a no-audio sticks3_pcm session, stops with
explicit text and paste true, then calls pressEnter through the existing button_short
event. Serialize both actions with a Mutex.

- [x] **Step 4: Implement repository state**

Expose StateFlow values for connection, candidates, bridge state, and busy action.
Discovery updates candidates; one result auto-connects and multiple results wait for
selection. HTTP 401 maps to invalid token. IO failures map to offline without deleting
the remembered candidate.

- [x] **Step 5: Run tests and commit**

    .\gradlew.bat :app:testDebugUnitTest --tests '*ManualSendCoordinatorTest'
    git add app/src/main/java/com/vibestick/android/data/BridgeRepository.kt app/src/test/java/com/vibestick/android/data/ManualSendCoordinatorTest.kt
    git commit -m "feat: implement voice and manual send rules"

### Task 6: Add Foreground Monitoring And One-Shot Notifications

**Files:**
- Create: app/src/main/java/com/vibestick/android/VibeStickApplication.kt
- Create: app/src/main/java/com/vibestick/android/service/NotificationFactory.kt
- Create: app/src/main/java/com/vibestick/android/service/BridgeMonitorService.kt
- Test: app/src/test/java/com/vibestick/android/data/AlertDeduplicatorTest.kt
- Modify: app/src/main/AndroidManifest.xml

- [x] **Step 1: Write failing deduplication tests**

    @Test
    fun terminalEventIsDeliveredOnce() {
        val dedupe = AlertDeduplicator("")
        assertTrue(dedupe.shouldNotify(Alert("done-1", AlertType.DONE, "done")))
        assertFalse(dedupe.shouldNotify(Alert("done-1", AlertType.DONE, "done")))
    }

    @Test
    fun emptyAndNoneAlertsAreIgnored() {
        val dedupe = AlertDeduplicator("")
        assertFalse(dedupe.shouldNotify(Alert("", AlertType.NONE, "")))
    }

- [x] **Step 2: Run tests and verify failure**

    .\gradlew.bat :app:testDebugUnitTest --tests '*AlertDeduplicatorTest'

- [x] **Step 3: Implement notification channels and mapping**

Create vibestick_monitor at low importance and vibestick_task_alerts at high
importance. Map DONE to task completed, ERROR to task failed, and APPROVAL to waiting
for confirmation. Use the bridge message as body. Persist an event ID only after
notification dispatch succeeds.

- [x] **Step 4: Implement BridgeMonitorService**

Call startForeground immediately. Poll GET /state every 2 seconds while connected.
Back off 5, 10, then 30 seconds on IO failures. On the first successful connection,
seed the current event ID without replaying it. Cancel the coroutine scope in
onDestroy. The persistent notification opens MainActivity.

- [x] **Step 5: Run tests, build, and commit**

    .\gradlew.bat :app:testDebugUnitTest
    .\gradlew.bat :app:assembleDebug
    git add app/src/main/java/com/vibestick/android/VibeStickApplication.kt app/src/main/java/com/vibestick/android/service app/src/main/AndroidManifest.xml app/src/test/java/com/vibestick/android/data/AlertDeduplicatorTest.kt
    git commit -m "feat: monitor bridge task alerts"

### Task 7: Build The Confirmed Compose UI And Verify

**Files:**
- Create: app/src/main/java/com/vibestick/android/MainActivity.kt
- Create: app/src/main/java/com/vibestick/android/ui/UiState.kt
- Create: app/src/main/java/com/vibestick/android/ui/MainViewModel.kt
- Create: app/src/main/java/com/vibestick/android/ui/MainScreen.kt
- Create: app/src/main/java/com/vibestick/android/ui/ConnectionSheet.kt
- Create: app/src/main/java/com/vibestick/android/ui/VibeStickTheme.kt
- Test: app/src/test/java/com/vibestick/android/ui/MainViewModelTest.kt
- Test: app/src/androidTest/java/com/vibestick/android/MainScreenTest.kt
- Modify: README.md

- [x] **Step 1: Write failing ViewModel tests**

Test that voice success reaches PASTED without Enter, manual success clears the input,
manual failure retains the input, permission denial produces a retryable error, and a
second send is ignored while busy.

- [x] **Step 2: Implement UiState and MainViewModel**

UiState contains connection state, computer name, provider/task status, manual text,
amplitude, busy state, discovered candidates, and status message. MainViewModel
collects repository flows, drives AudioRecorder, and exposes startVoice, finishVoice,
cancelVoice, updateText, sendText, rescan, selectBridge, and updateToken.

- [x] **Step 3: Implement the lower action layout**

Use a full-screen Scaffold. Put connection and task status at the top. Reserve flexible
space so the large blue voice circle sits low on the screen. Put a green circular Send
icon button to its right. Pin the manual text field to the bottom. Use fixed action
bounds so amplitude animation cannot move surrounding content.

The blue circle scales from 1.0 to 1.18 and translates only a few density-independent
pixels using local amplitude. Long press starts recording; release stops and uploads.
The green button sends only manual text. It is disabled when text is blank, bridge is
offline, or an action is busy.

Use Material icons Mic, Send, Refresh, and Settings with content descriptions. Use
Chinese UI labels. Do not show a fake live transcript.

- [x] **Step 4: Add connection sheet and runtime permissions**

The sheet lists multiple discovered computers, supports rescan, and accepts an
obscured optional token. MainActivity requests POST_NOTIFICATIONS on Android 13+ and
RECORD_AUDIO only when voice is first used. Start the foreground monitor after
notification permission or automatically on older Android.

- [x] **Step 5: Write Compose UI tests**

Verify the bottom input and voice/send controls are visible, blank input disables send,
typed text enables send when connected, busy disables repeated send, and an error
leaves typed text unchanged.

- [x] **Step 6: Run all tests and install on the API 35 emulator**

    .\gradlew.bat test
    Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList '-avd','TrailMate_API_35','-no-snapshot-load' -WindowStyle Hidden
    adb wait-for-device
    .\gradlew.bat :app:installDebug
    adb shell am start -n com.vibestick.android/.MainActivity
    adb exec-out uiautomator dump /dev/tty > qa-ui-home.xml
    adb exec-out screencap -p > qa-home.png

Expected: tests pass, install succeeds, UI tree includes the input and both controls,
and the screenshot is nonblank with no overlapping content.

Verified on the API 35 `TrailMate_API_35` emulator: 62 unit tests and 4 Compose
instrumented tests passed. The connected, microphone-denied, connection-sheet, and
IME-resized layouts were also inspected from screenshots and UIAutomator bounds.

- [ ] **Step 7: Validate on a physical phone and document results**

Pending: this requires an Android phone on the same Wi-Fi as the Windows bridge.
The emulator uses NAT and cannot validate UDP broadcast discovery or real focused-input
injection without risking an unintended Enter in the active Windows application.

On the same Wi-Fi as the installed Windows bridge:

1. Confirm automatic UDP discovery shows the computer name.
2. Focus a Codex input, long-press voice, speak, release, and verify the transcript is
   pasted without Enter.
3. Type manual phone text, tap green send, and verify paste followed by Enter.
4. Trigger Codex DONE and ERROR; verify one notification per new event ID while the
   app is backgrounded and while the phone is locked.
5. Verify invalid token, bridge offline, ASR failure, paste failure, and Enter failure
   remain visible and retryable.

Update README.md with build/install commands, Windows Firewall ports TCP 8765 and UDP
8766, SiliconFlow configuration staying on the PC, optional token setup, and the
requirement that VIBE_STICK_AUTO_ENTER remain off so voice does not submit.

- [x] **Step 8: Final verification and commit**

    .\gradlew.bat test
    .\gradlew.bat :app:assembleDebug
    git status --short
    git add app/src README.md
    git commit -m "feat: complete VibeStick Android companion"

Expected: all tests pass and app/build/outputs/apk/debug/app-debug.apk exists.

---

## Plan Self-Review

Coverage is explicit:

- StickS3-compatible UDP discovery and optional token: Tasks 2 and 3.
- Existing state polling and alert event-ID deduplication: Tasks 2 and 6.
- Computer-side SiliconFlow transcription and voice paste without Enter: Tasks 4 and 5.
- Manual phone text paste followed by Enter: Task 5.
- Lower blue circle, right-side green send, and bottom input: Task 7.
- Foreground service, DONE/ERROR/APPROVAL notifications, permissions, emulator QA,
  and physical LAN verification: Tasks 6 and 7.

The method names and data fields are consistent across tests and implementation tasks.
No protocol change to the installed Windows bridge is required. The existing bridge
default VIBE_STICK_AUTO_ENTER=off is an explicit compatibility requirement.
