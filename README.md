# Mic-Lock Application: Purpose and Behavioral Specification

This document outlines the core purpose of the Mic-Lock application and the specific behavioral requirements necessary to achieve its objectives. This specification serves as a guiding principle for all future development, ensuring the app maintains its intended functionality, especially regarding microphone route management and interaction with other applications.

## 1. Application Purpose

The primary purpose of the Mic-Lock application is to:

* **Ensure Reliable Microphone Access:** On devices with problematic built-in microphones (e.g., a non-working bottom mic), Mic-Lock aims to enable other applications (like WhatsApp) to reliably capture audio from a *working* microphone (e.g., the top array mic).
* **Optimize Microphone Route Management:** Leverage Android's audio policy to consistently establish a "good" (primary/array, multi-channel) microphone input route, allowing other apps to inherit this optimal route when they start recording.
* **Act as a Polite Background Holder:** Graciously yield microphone access to foreground applications while ensuring they utilize the established optimal microphone route, preventing silent recordings or use of non-functional microphones.
* **Maintain Battery Efficiency:** Achieve the above objectives with minimal impact on device battery life, offering a more efficient solution compared to alternatives like MyRecorder.

## 2. Core Problem Being Solved

Based on investigation findings:

* On devices like Pixel phones, the **bottom** built-in mic (`AUDIO_DEVICE_IN_BUILTIN_MIC, address @:bottom`) may be the **faulty** capsule.
* When **Mic-Lock + WhatsApp** record together, both bind to the **same input route/patch** whose **device address is `@:bottom`**. Android then **silences Mic-Lock** and lets WhatsApp use that *route*—but since that route is bottom, WhatsApp captures **silence**.
* When **MyRecorder + WhatsApp** record together, MyRecorder is frequently on a **2-channel 48 kHz device path** (array/primary front-end). When WhatsApp starts, Android **silences MyRecorder** and keeps WhatsApp on that **good (array/top) route**, so WhatsApp captures real audio.

**Key Insight:** The difference isn't that MyRecorder "unmutes" WhatsApp; it's that MyRecorder tends to be attached to a **good route** that the policy shares with WhatsApp, whereas without Mic-Lock, apps get attached to the **bad bottom route**.

## 3. Necessary Behavioral Specification

To fulfill its purpose, the Mic-Lock application *must* exhibit the following behaviors:

### 3.1 Microphone Route Selection and Establishment

Mic-Lock must prioritize and consistently land on the *primary/array* (good) input route, not problematic capsules (like the bottom mic). This involves:

* **Avoid Pinning Microphones:** Do **not** call `setPreferredDevice(...)` to pin a specific physical microphone capsule (e.g., "bottom" or "top") by default. The default selection should be "Auto".
* **Target Primary Array Path:** Use audio capture configurations (e.g., specific sample rates, channel counts, and audio sources) that Android's audio policy maps to the **primary/array** path. This path is typically observed as a `dev=2ch 48000` configuration on the device side.
* **Multi-Channel Route Preference:** Dynamically request stereo (2-channel) input when device capabilities allow, as this tends to route to the primary microphone array rather than single-capsule routes.

### 3.2 Dual Recording Strategy

Mic-Lock implements a dual-strategy approach with intelligent fallback:

* **MediaRecorder Priority:** By default, attempt MediaRecorder first as it tends to establish better routes on many devices.
* **AudioRecord Fallback:** If MediaRecorder fails or user prefers AudioRecord mode, fall back to AudioRecord with enhanced route validation.
* **Route Validation:** After establishing any recording session, validate the actual route obtained and switch methods if a bad route is detected.
* **Smart Retry Logic:** If landing on `@:bottom` or non-primary array routes, immediately stop and retry with the alternative recording method.

### 3.3 Polite Background Holding and Yielding

When another application (e.g., WhatsApp) starts recording and contention for the microphone occurs, Mic-Lock must gracefully yield control:

* **Detect Silencing:** Actively detect when its audio client is silenced (e.g., `isClientSilenced == true` through `AudioRecordingCallback`).
* **Prompt Release:** Upon detection of silencing, immediately **stop and release** its microphone input to free the route for the foreground application.
* **Polite Re-acquisition Logic:** Do not attempt to re-acquire the microphone immediately after being unsilenced. Instead, employ a polite backoff strategy:
    *   **Cooldown Period:** Wait for a minimum cooldown period (e.g., 3 seconds) after being silenced to prevent rapid re-acquisition.
    *   **Active Recorder Check:** Before attempting to re-acquire, verify that no other applications are actively recording.
    *   **Exponential Backoff:** If other recorders are still active after the cooldown, continue to wait, applying an exponential backoff to periodically re-check for an opportunity to safely re-acquire the microphone without causing contention.

### 3.4 Enhanced Route Validation

Mic-Lock must validate the *actual* microphone route it obtains, not just its requested settings:

* **Multi-Channel Validation:** Verify that the established route provides the requested channel count (preferably 2-channel for primary array access).
* **Primary Array Detection:** Check microphone position coordinates (Y >= 0.0f typically indicates primary array, not bottom mic).
* **Device Address Inspection:** Log and validate that the route is not using `@:bottom` address when possible.
* **Session ID Tracking:** Monitor audio session IDs to confirm route inheritance by foreground applications.

### 3.5 Avoid Bias-Inducing Modes

Mic-Lock should avoid requesting audio modes or flags that might inadvertently bias Android's audio policy towards niche or single-capsule microphone paths:

* **Default Audio Source:** Stick to the default `MediaRecorder.AudioSource.MIC` (or equivalent for `AudioRecord`).
* **No Special Processing:** Do **not** request `UNPROCESSED`, `FAST/RAW`, or similar modes that trigger raw or AAudio paths.
* **No Communication Bias:** Do **not** set `MODE_IN_COMMUNICATION`, initiate SCO connections, or aggressively request audio focus in a manner that biases routing to communication-specific paths.

### 3.6 Proper Foreground Service (FGS) Lifecycle and Android 14+ Compatibility

Mic-Lock must integrate correctly with Android's Foreground Service lifecycle to ensure stable policy classification, while gracefully handling Android 14+ background service restrictions:

* **Open Input After FGS Start:** The microphone input should only be opened *after* the Foreground Service is fully running and its notification is visible.
* **Persistent Notification:** Maintain a clear, ongoing notification that indicates the service status and allows user control.
* **Graceful Shutdown:** Properly clean up resources and release wake locks when the service is stopped.
* **Android 14+ Background Resilience:** Handle `ForegroundServiceStartNotAllowedException` gracefully by:
  - Wrapping all `startForeground()` calls in try-catch blocks
  - Allowing microphone holding to continue even if foreground service activation fails
  - Distinguishing between user-initiated and boot-initiated service starts
  - Using regular `startService()` for already-running services to avoid background restrictions
* **Screen State Integration:** Properly coordinate with screen state changes to manage foreground service lifecycle without triggering Android's background service restrictions.

### 3.7 User Interface and Preferences

* **Compatibility Mode Toggle:** Provide a runtime toggle between MediaRecorder and AudioRecord modes, with MediaRecorder as the default (higher battery usage but better route establishment).
* **Enhanced Status Reporting:** The UI should clearly display status messages, such as:
  - "Mic-lock is ON" when actively holding microphone
  - "Paused — mic in use by another app" when silenced by foreground app
  - Current recording method (MediaRecorder/AudioRecord) and device information
* **Auto-Selection Default:** The app defaults to automatic microphone selection rather than manual device pinning.
* **Battery Usage Awareness:** Clearly communicate to users that MediaRecorder mode uses more battery but provides better compatibility.

## 4. Technical Implementation Requirements

### 4.1 Audio Configuration

* **Sample Rate:** Use 48kHz as the primary sample rate to match common device capabilities.
* **Channel Configuration:** Request stereo (2-channel) input when device supports it, falling back to mono if necessary.
* **Audio Format:** Use PCM 16-bit encoding for broad compatibility.
* **Buffer Management:** Use appropriate buffer sizes (minimum 4KB) to ensure stable recording.

### 4.2 Route Validation Logic

```kotlin
// Core validation criteria:
// 1. Not on primary array (Y coordinate < 0.0f typically indicates bottom mic)
// 2. Single channel when multi-channel was requested
// 3. Device address indicating bottom microphone (@:bottom)
val isBadRoute = !routeInfo.isOnPrimaryArray || 
                (channelMask == AudioFormat.CHANNEL_IN_STEREO && actualChannelCount < 2) ||
                routeInfo.deviceAddress == "@:bottom"
```

### 4.3 Fallback Strategy

1. **Primary Attempt:** Try user's preferred recording method (MediaRecorder by default)
2. **Route Validation:** Check if the established route meets quality criteria
3. **Fallback Trigger:** If bad route detected, immediately switch to alternative method
4. **Retry Logic:** If both methods fail, wait 2 seconds and retry the entire process
5. **User Feedback:** Update UI to reflect current status and any method switches

### 4.4 Android 14+ Compatibility Handling

* **Exception Resilience:** All `startForeground()` calls must be wrapped in try-catch blocks to handle `ForegroundServiceStartNotAllowedException`
* **Service Communication:** Use `context.startService()` instead of `ContextCompat.startForegroundService()` when sending actions to already-running services
* **Graceful Degradation:** Allow core microphone holding functionality to continue even when foreground service activation fails due to background restrictions
* **Boot vs User Start Differentiation:** Track service start origin to apply appropriate foreground service activation strategies

## 5. Testing and Validation

To validate that Mic-Lock is working correctly:

1. **Baseline Test:** Start Mic-Lock → check `dumpsys audio` → verify `dev=2ch 48000` and non-`@:bottom` address
2. **Integration Test:** Start WhatsApp recording → verify same patch ID inheritance with Mic-Lock silenced and WhatsApp active
3. **Route Quality Test:** Confirm that other apps record actual audio (not silence) when Mic-Lock is running
4. **Battery Efficiency Test:** Compare battery usage against alternatives like MyRecorder

## 6. Success Criteria

The application is functioning correctly when:

* Other recording apps (WhatsApp, voice recorders, etc.) capture clear audio instead of silence
* Mic-Lock consistently establishes primary array routes (not bottom microphone routes)
* The app gracefully yields to foreground applications while maintaining good route inheritance
* Battery usage remains reasonable, especially in AudioRecord mode
* Users can toggle between recording methods based on their device's specific characteristics

## 7. Maintenance Guidelines

When modifying this application:

* **Preserve Route Establishment Logic:** Never remove or significantly alter the route validation and fallback mechanisms
* **Maintain Dual Strategy:** Keep both MediaRecorder and AudioRecord implementations with intelligent switching
* **Respect Silencing Behavior:** Always yield promptly to foreground applications
* **Test Route Inheritance:** Verify that changes don't break the core route-sharing behavior with other apps
* **Monitor Battery Impact:** Ensure modifications don't significantly increase power consumption
* **Validate on Problem Devices:** Test specifically on devices known to have bottom microphone issues
* **Preserve Android 14+ Compatibility:** Maintain try-catch blocks around `startForeground()` calls and the distinction between service communication methods
* **Test Background Scenarios:** Verify that microphone holding continues to work even when foreground service activation is denied by the system

## 8. Android Version Compatibility

### 8.1 Android 14+ (API 34+) Considerations

* **Background Foreground Service Restrictions:** Android 14+ significantly restricts when apps can start foreground services from the background
* **Screen State Handling:** The app handles screen ON/OFF cycles gracefully, allowing microphone holding to continue even when foreground service activation is restricted
* **Service Communication Strategy:** Uses appropriate service communication methods based on service state to avoid triggering system restrictions
* **Graceful Degradation:** Core functionality (microphone route establishment and holding) continues to work even when foreground service features are limited by system policies

### 8.2 Backward Compatibility

* **API Level Support:** Maintains compatibility with older Android versions while leveraging newer APIs when available
* **Progressive Enhancement:** Uses feature detection to enable advanced capabilities on supported devices

This specification ensures that Mic-Lock continues to solve the core problem of enabling reliable microphone access for other applications on devices with faulty microphone hardware.