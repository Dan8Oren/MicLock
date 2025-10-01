# Setup Guide for Mic-Lock

This guide will walk you through installing and configuring Mic-Lock to ensure optimal performance on your Android device.

## 1. Installation

### Download the APK
Download the latest stable APK from the repository: [`app/release/mic-lock_stable.apk`](../app/release/mic-lock_stable.apk)

### Install the APK
1. Locate the downloaded APK file (e.g., `mic-lock_stable.apk`) in your device's file manager.
2. Tap on the APK file to begin installation.
3. If prompted, you may need to enable "Install from Unknown Sources" in your device's settings:
   - **Android 8+**: Settings → Apps & notifications → Special app access → Install unknown apps → [Your browser/file manager] → Allow from this source
   - **Older Android**: Settings → Security → Unknown sources (enable)
4. Follow the on-screen prompts to complete the installation.

## 2. Initial Configuration & Permissions

After installation, launch Mic-Lock for the first time:

### Required Permissions
1. **Grant Microphone Permission**: The app will request access to your microphone. This is essential for its functionality. **Please grant this permission.**
2. **Grant Notification Permission**: The app will request permission to send notifications. This is used for the persistent service notification, which helps keep the app running in the background. **Please grant this permission.**

### Why These Permissions Are Needed
- **Microphone**: To establish and manage audio routes, not to record audio
- **Notifications**: To display service status and provide quick controls

## 3. Disable Battery Optimizations (Critical!)

To ensure Mic-Lock runs uninterrupted in the background, you **must** disable battery optimizations for it:

### General Steps (may vary by device):
1. Go to your device's **Settings**
2. Navigate to **Apps** or **Apps & notifications**
3. Find **Mic-Lock** in the list of installed apps and tap on it
4. Look for **Battery** or **Battery usage**
5. Select **Unrestricted** or **Don't optimize** (avoid "Optimized" or "Restricted")

### Device-Specific Instructions:

**Google Pixel/Stock Android:**
- Settings → Apps → Mic-Lock → Battery → Battery optimization → Not optimized

**Samsung Galaxy:**
- Settings → Apps → Mic-Lock → Battery → Optimize battery usage → Turn off
- Also: Settings → Device care → Battery → App power management → Apps that won't be put to sleep → Add Mic-Lock

**OnePlus:**
- Settings → Apps → Mic-Lock → Battery → Battery optimization → Don't optimize

**Xiaomi/MIUI:**
- Settings → Apps → Manage apps → Mic-Lock → Battery saver → No restrictions
- Also: Settings → Apps → Manage apps → Mic-Lock → Autostart → Enable

## 4. Start the Service

1. Open the Mic-Lock app
2. Tap the **"Start"** button on the main screen to activate the microphone protection service
3. You should see a persistent notification indicating that Mic-Lock is active
4. The app status should show **"ON"** when running properly

## 5. Test Mic-Lock

After starting the service, test its functionality:

1. **Open WhatsApp** (or another recording app)
2. **Record a voice message** or make a call
3. **Verify that audio is now being recorded/transmitted correctly**
4. If the Mic-Lock status shows **"PAUSED"**, this is normal - it means another app is using the microphone

## 6. Configure Settings (Optional)

You can adjust Mic-Lock's behavior in the app settings:

### Recording Modes {#recording-modes}
- **MediaRecorder Mode** (Default): Better compatibility, slightly higher battery usage
- **AudioRecord Mode**: More battery-efficient, works well on most modern devices

### When to Switch Modes
- Try **AudioRecord Mode** first if you're concerned about battery life
- Switch to **MediaRecorder Mode** if you experience issues with audio quality or app compatibility

## Troubleshooting Common Setup Issues

### App Won't Install
- Ensure "Install from Unknown Sources" is enabled for your browser/file manager
- Check that you have enough storage space
- Try downloading the APK again if it may be corrupted

### Permissions Not Requested
- Manually grant permissions: Settings → Apps → Mic-Lock → Permissions
- Enable Microphone and Notifications permissions

### Service Stops Frequently
- Double-check battery optimization settings
- Some aggressive battery management systems may require additional steps
- Consider adding Mic-Lock to any "protected apps" or "auto-start" lists in your device settings

### Still Having Issues?
- Check the [Troubleshooting Guide](troubleshooting.md) for more detailed solutions
- Report device-specific issues using our [Device Compatibility Report](https://github.com/yourusername/mic-lock/issues/new?template=device_compatibility.md) template

## Next Steps

Once Mic-Lock is properly set up and running:
- The app will continue working in the background
- Test with various recording apps to ensure compatibility
- Monitor battery usage and adjust the recording mode if needed
- The service will automatically restart after device reboots (if battery optimization is disabled)

Your microphone issues should now be resolved!