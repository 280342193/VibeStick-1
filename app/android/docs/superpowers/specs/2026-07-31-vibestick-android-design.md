# VibeStick Android LAN Companion

Date: 2026-07-31

Status: Design approved for implementation; written-spec review pending

## Goal

Build an Android app for the existing VibeStick Windows bridge. The app stays on the
same local network as the Windows computer, discovers the bridge using the protocol
already implemented by StickS3, monitors Codex task state, and controls the focused
desktop input without a cloud relay.

The primary interaction is intentionally small:

- Hold the blue circle to record voice.
- Release it to let the Windows bridge run the configured SiliconFlow transcription
  and paste the result into the computer's currently focused input.
- Type optional text in the bottom Android input.
- Tap the green send button to paste that text into the focused computer input and
  press Enter.

## Scope And Non-goals

In scope:

- Native Android app using Kotlin and Jetpack Compose.
- LAN-only discovery, state monitoring, audio upload, paste, and Enter submission.
- Background task notifications while the app is not in the foreground.
- Chinese-first UI with the confirmed lower action area.
- Optional bridge token configuration for installations that use one.

Out of scope:

- Cloud push, FCM, user accounts, or WAN access.
- Android-side cloud speech recognition.
- Changes to StickS3 firmware.
- Streaming partial transcription.
- Running or controlling Codex directly on Android.

## Existing Protocol Contract

The Android app reuses the current VibeStick protocol:

1. Send the same UDP discovery packet as StickS3 to broadcast port `8766`:

   ```json
   {"type":"vibestick_discover","token":"<bridge-token>","device":"VibeStick"}
   ```

2. Accept responses with `type: "vibestick_bridge"`. The sender IP is the bridge
   host and `port` is the HTTP port, defaulting to `8765` when omitted.

3. Poll `GET /state`. The response's `alert.event_id` is the stable deduplication
   key for terminal task notifications. `DONE`, `ERROR`, and `APPROVAL` are the
   notification states.

4. Use the protected recording endpoints with the existing headers:

   - `POST /recording/start`
   - `POST /recording/audio?session_id=...`
   - `POST /recording/stop`

   Audio is little-endian signed 16-bit PCM, 16 kHz, mono. The Android recorder
   labels the source as `sticks3_pcm` so the bridge does not start its own desktop
   microphone recorder.

5. Use `POST /event` with `{"event":"button_short", "source":"android_text_send"}`
   after a successful manual paste to reuse the bridge's existing Enter injection.

Protected requests include `X-Vibe-Stick-Token` when the user has configured a
shared token. Firmware identity headers use an Android-specific app version while
retaining the existing VibeStick name and HTTP transport value.

## Architecture

### Android UI

Jetpack Compose renders one focused home screen:

- Top: connection, computer name, active provider, and Codex task status.
- Lower action area: the large blue hold-to-speak circle and the green send button
  on its right.
- Bottom: a manual text input pinned to the bottom edge.
- Inline state text: discovering, connected, recording, uploading, transcribing,
  pasted, sent, or error.

The circle's visual motion comes from local microphone amplitude, so it responds
immediately even while the audio is waiting to upload or the PC is transcribing.
The app does not show a fake live transcript because the current SiliconFlow path is
batch transcription on the computer.

### Bridge discovery

`DiscoveryManager` sends a broadcast packet to `255.255.255.255:8766` and listens
for responses for about 1.4 seconds, matching the StickS3 behavior. It also retries
the last known host to handle networks that filter broadcast packets. Responses are
deduplicated by host and port. One candidate connects automatically; multiple
candidates are shown with computer name and bridge version.

The selected candidate is stored locally. Discovery repeats when the bridge is
offline or when the user taps the rescan action. No IP range scan is performed.

### Bridge client and monitor

`BridgeClient` owns HTTP request construction, headers, timeout handling, JSON
decoding, and protected-token handling. `BridgeMonitorService` is an Android
foreground service with a persistent low-importance notification. It polls `/state`
every two seconds while connected, backs off while offline, and publishes state to
the Compose screen.

The service stores the last delivered `alert.event_id`. On first connection it
adopts the current event without replaying an old alert; only later event IDs produce
notifications. A reconnection does not duplicate an already delivered event.

### Recording controller

`RecordingController` uses `AudioRecord` with 16 kHz, mono, 16-bit PCM. It buffers
the recording locally because the bridge currently accepts a completed audio upload
rather than a streaming session. The app caps the session below the bridge's
default 2 MB upload limit, about 60 seconds at this format.

Long press starts the session. Release stops the recorder, uploads the PCM with the
required audio headers, and calls `/recording/stop` with `paste: true`. The voice
path never calls `button_short`, so it does not intentionally press Enter. The
existing bridge default `VIBE_STICK_AUTO_ENTER` must remain off for this distinction
to hold; this is the current project default.

### Manual text sender

The manual sender uses a no-audio recording session to reuse the existing explicit
text path:

1. `POST /recording/start` with `audio_source: "sticks3_pcm"`.
2. `POST /recording/stop` with the input as `text` and `paste: true`.
3. On successful paste, `POST /event` with `event: "button_short"` to press Enter.

The input is cleared only after both paste and Enter succeed. A failed request leaves
the text intact for retry. A send mutex disables repeat taps while this sequence is
running.

## Notifications And Permissions

The app requests only:

- `RECORD_AUDIO` when the user first starts voice input.
- `POST_NOTIFICATIONS` on Android 13 and newer.
- Internet, network state, and foreground-service permissions required for LAN
  monitoring.

The foreground notification identifies the connected computer and exposes a tap
action that opens the app. Terminal task events use a separate high-importance
notification channel with sound/vibration enabled by the system channel settings.
The same event is also shown inline when the app is visible.

Recording errors, authentication errors, and bridge disconnects update the inline
state. They do not create a repeated notification loop. Task `DONE`, `ERROR`, and
`APPROVAL` events always create one local notification per new event ID.

## Failure Handling

- No discovery response: show the LAN discovery state and offer rescan.
- HTTP connection failure: mark the bridge offline and retry with backoff.
- HTTP 401: show an invalid-token state and open token settings.
- Empty, too-short, or silent audio: skip upload and keep the user in a retryable
  recording state.
- Transcription failure: show the bridge message and do not send an empty prompt.
- Paste failure: retain manual text or show a voice failure state.
- Paste succeeds but Enter fails: retain manual text and show that it was pasted but
  not submitted.
- Multiple bridge devices: require an explicit computer choice, then remember it.

The app assumes the installed Windows bridge is reachable on the LAN and that the
Windows firewall permits TCP `8765` and UDP `8766`. When a shared token is enabled,
the user enters the same token in the Android connection settings; it is stored only
locally on the phone.

## Verification Strategy

Unit tests cover discovery response parsing, candidate deduplication, state mapping,
alert event-ID deduplication, token/header construction, recording request payloads,
and the manual paste-then-Enter coordinator.

The app includes a fake bridge test seam for deterministic HTTP responses and task
alerts. Android UI tests cover the lower action layout, long-press recording states,
disabled/duplicate send behavior, and error retention. A real Android device on the
same Wi-Fi validates UDP discovery, Windows bridge access, microphone capture,
SiliconFlow transcription, focused-window paste, Enter injection, and lock-screen
notifications. Emulator QA validates install, launch, permission prompts, UI state,
and local notification rendering; it does not replace real-device LAN validation.

## Acceptance Criteria

- A phone on the same Wi-Fi discovers the installed Windows bridge without manual IP
  entry.
- A long press records locally, animates the blue circle with microphone volume, and
  pastes the PC-generated transcript into the focused desktop input without Enter.
- Manual text in the bottom input pastes to the focused desktop input and submits with
  Enter when the green button is tapped.
- Codex DONE and ERROR events each produce one phone notification, including while
  the app is backgrounded and the foreground monitor is alive.
- Offline, permission, authentication, transcription, paste, and Enter failures are
  visible and retryable without losing manual text.
- The app builds and installs as a debug APK, and the defined unit/UI/emulator tests
  pass.
