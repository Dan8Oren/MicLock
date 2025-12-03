# Mic-Lock Application: Purpose and Behavioral Specification

This document outlines the core purpose of the Mic-Lock application and the specific behavioral requirements necessary to achieve its objectives. This specification serves as a guiding principle for all future development, ensuring the app maintains its intended functionality, especially regarding microphone route management and interaction with other applications.

## 1. Application Purpose

The primary purpose of the Mic-Lock application is to:

* **Ensure Reliable Microphone Access:** On devices with problematic built-in microphones (e.g., a non-working bottom mic), Mic-Lock aims to enable other applications (like WhatsApp) to reliably capture audio from a *working* microphone (e.g., the top array mic).
* **Optimize Microphone Route Management:** Leverage Android's audio policy to consistently establish a "good" (primary/array, multi-channel) microphone input route, allowing other apps to inherit this optimal route when they start recording.
* **Act as a Polite Background Holder:** Graciously yield microphone access to foreground applications while ensuring they utilize the established optimal microphone route, preventing silent recordings or use of non-functional microphones.
* **Maintain Battery Efficiency:** Achieve the above objectives with minimal impact on device battery life, offering a more efficient solution compared to alternatives like MyRecorder.

## 2. Necessary Behavioral Specification

To fulfill its purpose, the Mic-Lock application *must* exhibit the following behaviors:

### 2.1 Microphone Route Selection and Establishment

Mic-Lock must prioritize and consistently land on the *primary/array* (good) input route, not problematic capsules (like the bottom mic). This involves:

* **Avoid Pinning Microphones:** Do **not** call `setPreferredDevice(...)` to pin a specific physical microphone capsule (e.g., "bottom" or "top") by default. The default selection should be "Auto".
* **Target Primary Array Path:** Use audio capture configurations (e.g., specific sample rates, channel counts, and audio sources) that Android's audio policy maps to the **primary/array** path. This path is typically observed as a `dev=2ch 48000` configuration on the device side.
* **Multi-Channel Route Preference:** Dynamically request stereo (2-channel) input when device capabilities allow, as this tends to route to the primary microphone array rather than single-capsule routes.

### 2.2 Dual Recording Strategy

Mic-Lock implements a dual-strategy approach with intelligent fallback:

* **MediaRecorder Priority:** By default, attempt MediaRecorder first as it tends to establish better routes on many devices.
* **AudioRecord Fallback:** If MediaRecorder fails or user prefers AudioRecord mode, fall back to AudioRecord with enhanced route validation.
* **Route Validation:** After establishing any recording session, validate the actual route obtained and switch methods if a bad route is detected.
* **Smart Retry Logic:** If landing on `@:bottom` or non-primary array routes, immediately stop and retry with the alternative recording method.

### 2.3 Polite Background Holding and Yielding

When another application (e.g., WhatsApp) starts recording and contention for the microphone occurs, Mic-Lock must gracefully yield control:

* **Detect Silencing:** Actively detect when its audio client is silenced (e.g., `isClientSilenced == true` through `AudioRecordingCallback`).
* **Prompt Release:** Upon detection of silencing, immediately **stop and release** its microphone input to free the route for the foreground application.
* **Polite Re-acquisition Logic:** Do not attempt to re-acquire the microphone immediately after being unsilenced. Instead, employ a polite backoff strategy:
    *   **Cooldown Period:** Wait for a minimum cooldown period (e.g., 3 seconds) after being silenced to prevent rapid re-acquisition.
    *   **Active Recorder Check:** Before attempting to re-acquire, verify that no other applications are actively recording.
    *   **Exponential Backoff:** If other recorders are still active after the cooldown, continue to wait, applying an exponential backoff to periodically re-check for an opportunity to safely re-acquire the microphone without causing contention.

### 2.4 Enhanced Route Validation

Mic-Lock must validate the *actual* microphone route it obtains, not just its requested settings:

* **Multi-Channel Validation:** Verify that the established route provides the requested channel count (preferably 2-channel for primary array access).
* **Primary Array Detection:** Check microphone position coordinates (Y >= 0.0f typically indicates primary array, not bottom mic).
* **Device Address Inspection:** Log and validate that the route is not using `@:bottom` address when possible.
* **Session ID Tracking:** Monitor audio session IDs to confirm route inheritance by foreground applications.

### 2.5 Avoid Bias-Inducing Modes

Mic-Lock should avoid requesting audio modes or flags that might inadvertently bias Android's audio policy towards niche or single-capsule microphone paths:

* **Default Audio Source:** Stick to the default `MediaRecorder.AudioSource.MIC` (or equivalent for `AudioRecord`).
* **No Special Processing:** Do **not** request `UNPROCESSED`, `FAST/RAW`, or similar modes that trigger raw or AAudio paths.
* **No Communication Bias:** Do **not** set `MODE_IN_COMMUNICATION`, initiate SCO connections, or aggressively request audio focus in a manner that biases routing to communication-specific paths.

### 2.6 Proper Foreground Service (FGS) Lifecycle and Android 14+ Compatibility

Mic-Lock must integrate correctly with Android's Foreground Service lifecycle to ensure stable policy classification, while gracefully handling Android 14+ background service restrictions and implementing intelligent delayed activation:

* **Open Input After FGS Start:** The microphone input should only be opened *after* the Foreground Service is fully running and its notification is visible.
* **Persistent Notification:** Maintain a clear, ongoing notification that indicates the service status and allows user control.
* **Graceful Shutdown:** Properly clean up resources and release wake locks when the service is stopped.
* **Android 14+ Background Resilience:** Handle `ForegroundServiceStartNotAllowedException` gracefully by:
  - Wrapping all `startForeground()` calls in try-catch blocks
  - Allowing microphone holding to continue even if foreground service activation fails
  - Distinguishing between user-initiated and boot-initiated service starts
  - Using regular `startService()` for already-running services to avoid background restrictions
* **Screen State Integration:** To prevent termination by the OS, the service remains in the foreground at all times when active.
  - When the screen turns **off**, the service pauses microphone usage to save battery but **does not exit the foreground state**. The notification is updated to show a "Paused (Screen off)" status.
  - When the screen turns **on**, the service implements intelligent delayed activation with configurable delays (default 1.3 seconds) to prevent unnecessary battery drain during brief screen interactions.
* **Delayed Activation Management:** The service must properly handle delayed microphone re-activation:
  - Start foreground service immediately when delay is scheduled to comply with Android 14+ restrictions
  - Cancel pending delays if screen turns off during delay period
  - Restart delay from beginning if screen turns on again during existing delay
  - Respect existing service states (manual stops, active sessions, paused by other apps) when applying delays

### 2.7 Intelligent Screen State Management

Mic-Lock must implement configurable delayed activation to optimize battery usage while maintaining responsive behavior:

* **Configurable Delay Period:** Provide user-configurable delay (0-5000ms) before re-activating microphone when screen turns on
* **Smart Cancellation Logic:** Cancel pending activation if screen turns off during delay period, preventing unnecessary operations
* **Race Condition Handling:** Handle rapid screen state changes with latest-event-wins strategy and proper coroutine job management
* **State Validation:** Ensure delays only apply when appropriate (service paused by screen-off, not manually stopped or already active)
* **Foreground Service Coordination:** Start foreground service immediately when delay is scheduled to comply with Android 14+ background restrictions
* **Notification Updates:** Update service notification to reflect delay status with countdown display during delay periods
* **Manual Override Support:** Allow immediate activation through Quick Settings tile or manual service start, cancelling any pending delays

### 2.8 User Interface and Preferences

*   **Quick Settings Tile**: A state-aware tile provides at-a-glance status and one-tap control. It must reflect the service's state (On, Off, Paused) and become unavailable if permissions are missing.

* **Compatibility Mode Toggle:** Provide a runtime toggle between MediaRecorder and AudioRecord modes, with MediaRecorder as the default (higher battery usage but better route establishment).
* **Enhanced Status Reporting:** The UI should clearly display status messages, such as:
  - "Mic-lock is ON" when actively holding microphone
  - "Paused — mic in use by another app" when silenced by foreground app
  - **"Paused (Screen off)"** when the screen is off to conserve battery
  - Current recording method (MediaRecorder/AudioRecord) and device information
* **Auto-Selection Default:** The app defaults to automatic microphone selection rather than manual device pinning.
* **Battery Usage Awareness:** Clearly communicate to users that MediaRecorder mode uses more battery but provides better compatibility.
* **Battery Optimization Exemption:** Upon first launch, the app prompts the user to grant an exemption from battery optimizations. This is critical to prevent the Android system from terminating the service during long periods of device inactivity, ensuring continuous background operation.

### 2.9 Debug Logging and Diagnostics

To facilitate troubleshooting of audio routing issues, Mic-Lock provides comprehensive debug logging capabilities:

* **On-Demand Recording:** Users can start debug log recording through the overflow menu (⋮) → "Debug Tools"
* **Automatic Safety Mechanisms:**
  - 30-minute auto-stop to prevent battery drain and storage exhaustion
  - Automatic cleanup of temporary files when app closes
  - Visual recording indicator with elapsed time counter
* **Comprehensive Data Collection:**
  - Application logs (logcat filtered to Mic-Lock process only)
  - System audio state (dumpsys audio, telecom, media.session, media.audio_policy, media.audio_flinger)
  - Device metadata (manufacturer, model, Android version, app version)
  - Service state at time of collection
* **Privacy-Conscious Design:**
  - Only captures Mic-Lock app logs (no other app data)
  - No call audio content, contacts, phone numbers, or location data
  - Explicit user action required to share logs
* **Persistent Storage:**
  - Logs saved to Downloads/miclock_logs folder with timestamped filenames
  - Files persist after app uninstall for later reference
  - Notification with "Share" action for easy log distribution
* **Graceful Error Handling:**
  - Continues collection even if some components fail
  - Provides context about which failures are relevant to specific issues
  - Clear error messages guide users on next steps
* **Crash Detection and Recovery:**
  - Automatically saves debug logs if app crashes during recording
  - Shows notification with crash log location
  - On next launch, offers to report crash on GitHub with pre-filled issue template
  - Crash logs include exception details and stack traces
* **User-Friendly Sharing:**
  - Standard Android share sheet integration
  - Direct GitHub issue creation with pre-filled crash reports
  - Feedback mechanism for bug reports and feature requests
  - About screen with app information and repository link

### 2.10 Service Resilience and User Experience

To ensure the service remains active and is easy to manage, Mic-Lock implements several resilience features:

*   **Enhanced Foreground Service:** The foreground service notification is configured with a low priority and service category, making it "sticky" and less likely to be dismissed or have its associated service terminated by the Android system during low-memory situations.
*   **User-Friendly Reactivation:** If the service is terminated for any reason (e.g., by the system), a "Mic-Lock Stopped" notification is displayed. A single tap on this notification immediately restarts the service, providing a quick and seamless way for the user to restore functionality. This restart notification is automatically dismissed upon a successful restart.

*   **Robust Tile-Initiated Start**: To handle modern Android background start restrictions, initiating the service from the Quick Settings tile must follow a robust fallback mechanism. If a direct foreground service start fails, the application must automatically launch the `MainActivity` to complete the start request reliably.

*   **Always-On Foreground Service:** As the primary resilience strategy, the service remains in the foreground even when the screen is off. This high-priority status drastically reduces the likelihood of the OS terminating the process.

## 3. Technical Implementation Requirements

### 3.1 Audio Configuration

* **Sample Rate:** Use 48kHz as the primary sample rate to match common device capabilities.
* **Channel Configuration:** Request stereo (2-channel) input when device supports it, falling back to mono if necessary.
* **Audio Format:** Use PCM 16-bit encoding for broad compatibility.
* **Buffer Management:** Use appropriate buffer sizes (minimum 4KB) to ensure stable recording.

### 3.2 Route Validation Logic

```kotlin
// Core validation criteria:
// 1. Not on primary array (Y coordinate < 0.0f typically indicates bottom mic)
// 2. Single channel when multi-channel was requested
// 3. Device address indicating bottom microphone (@:bottom)
val isBadRoute = !routeInfo.isOnPrimaryArray || 
                (channelMask == AudioFormat.CHANNEL_IN_STEREO && actualChannelCount < 2) ||
                routeInfo.deviceAddress == "@:bottom"
```

### 3.3 Fallback Strategy

1. **Primary Attempt:** Try user's preferred recording method (MediaRecorder by default)
2. **Route Validation:** Check if the established route meets quality criteria
3. **Fallback Trigger:** If bad route detected, immediately switch to alternative method
4. **Retry Logic:** If both methods fail, wait 2 seconds and retry the entire process
5. **User Feedback:** Update UI to reflect current status and any method switches

### 3.4 Android 14+ Compatibility Handling

* **Exception Resilience:** All `startForeground()` calls must be wrapped in try-catch blocks to handle `ForegroundServiceStartNotAllowedException`
* **Service Communication:** Use `context.startService()` instead of `ContextCompat.startForegroundService()` when sending actions to already-running services
* **Graceful Degradation:** Allow core microphone holding functionality to continue even when foreground service activation fails due to background restrictions
* **Boot vs User Start Differentiation:** Track service start origin to apply appropriate foreground service activation strategies

## 4. Testing and Validation

To validate that Mic-Lock is working correctly:

1. **Baseline Test:** Start Mic-Lock → check `dumpsys audio` → verify `dev=2ch 48000` and non-`@:bottom` address
2. **Integration Test:** Start WhatsApp recording → verify same patch ID inheritance with Mic-Lock silenced and WhatsApp active
3. **Route Quality Test:** Confirm that other apps record actual audio (not silence) when Mic-Lock is running
4. **Battery Efficiency Test:** Compare battery usage against alternatives like MyRecorder

## 5. Success Criteria

The application is functioning correctly when:

* Other recording apps (WhatsApp, voice recorders, etc.) capture clear audio instead of silence
* Mic-Lock consistently establishes primary array routes (not bottom microphone routes)
* The app gracefully yields to foreground applications while maintaining good route inheritance
* Battery usage remains reasonable, especially in AudioRecord mode
* Users can toggle between recording methods based on their device's specific characteristics

## 6. Maintenance Guidelines

When modifying this application:

* **Preserve Route Establishment Logic:** Never remove or significantly alter the route validation and fallback mechanisms
* **Maintain Dual Strategy:** Keep both MediaRecorder and AudioRecord implementations with intelligent switching
* **Respect Silencing Behavior:** Always yield promptly to foreground applications
* **Test Route Inheritance:** Verify that changes don't break the core route-sharing behavior with other apps
* **Monitor Battery Impact:** Ensure modifications don't significantly increase power consumption
* **Validate on Problem Devices:** Test specifically on devices known to have bottom microphone issues
* **Preserve Android 14+ Compatibility:** Maintain try-catch blocks around `startForeground()` calls and the distinction between service communication methods
* **Test Background Scenarios:** Verify that microphone holding continues to work even when foreground service activation is denied by the system

## 7. Android Version Compatibility

### 7.1 Android 14+ (API 34+) Considerations

* **Background Foreground Service Restrictions:** Android 14+ significantly restricts when apps can start foreground services from the background
* **Screen State Handling:** The app handles screen ON/OFF cycles by keeping the foreground service active to ensure reliability. When the screen is off, microphone usage is paused and the notification is updated to a "Paused" state.
* **Service Communication Strategy:** Uses appropriate service communication methods based on service state to avoid triggering system restrictions
* **Graceful Degradation:** Core functionality (microphone route establishment and holding) continues to work even when foreground service features are limited by system policies

### 7.2 Backward Compatibility

* **API Level Support:** Maintains compatibility with older Android versions while leveraging newer APIs when available
* **Progressive Enhancement:** Uses feature detection to enable advanced capabilities on supported devices

This specification ensures that Mic-Lock continues to solve the core problem of enabling reliable microphone access for other applications on devices with faulty microphone hardware.