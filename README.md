# Mic-Lock 🎤🔒

> **Fix microphone issues on Android devices by ensuring apps use working microphones instead of faulty ones**

## What is Mic-Lock?

Mic-Lock addresses a frustrating and common issue for **Google Pixel phone** users, especially those who have had their **screens replaced**. During screen replacement procedures, the **bottom microphone** can sometimes become damaged or disconnected, causing apps like WhatsApp, voice recorders, and video calling applications to record silence instead of actual audio.

This app was specifically **invented to fix this issue** by running discreetly in the background and intelligently **rerouting audio input requests** from other apps to a **working microphone** (typically the **earpiece microphone**). The solution is designed to be **battery-efficient** by politely "holding" onto a working microphone route and stepping aside when another app needs to record, passing on the correct audio path seamlessly.

## 🚨 Problem Solved

**Common issue**: After Google Pixel screen replacement → Bottom microphone damaged → Apps record silence

**Mic-Lock solution**: Automatically routes all apps to the working earpiece microphone

-   **WhatsApp voice messages are silent** ❌ → **Clear audio recordings** ✅
-   **Video calls have no audio** ❌ → **Perfect call quality** ✅
-   **Voice recorder apps capture nothing** ❌ → **Reliable recordings** ✅

## 📱 How It Works

Mic-Lock acts as a "polite background holder" that:
1.  **Detects Faulty Microphone**: Identifies when the default microphone path is compromised (typically the bottom mic on Pixel devices).
2.  **Secures Working Mic**: Establishes and holds a connection to your device's *working* earpiece microphone array in a battery-efficient manner.
3.  **Graceful Handover**: When other apps start recording, Mic-Lock gracefully releases its hold.
4.  **Correct Path Inheritance**: The other app then inherits the correctly routed audio path to the functional microphone instead of defaulting to the broken one.
5.  **Seamless Experience**: Your recordings and calls work perfectly without manual intervention!

## 🔧 Installation

### Option 1: Download APK (Recommended for most users)
1.  Download the latest stable APK: [`mic-lock_stable.apk`](app/release/mic-lock_stable.apk)
2.  Enable "Install from Unknown Sources" in your device settings if prompted (this is standard for sideloaded apps).
3.  Install the downloaded APK file.
4.  Grant **microphone** and **notification** permissions when the app first launches.
5.  **Crucially**, allow the app to ignore battery optimizations (usually found in App Info → Battery Usage or similar path) to ensure uninterrupted background operation.

### Option 2: Build from Source (For developers and advanced users)

```bash
git clone https://github.com/yourusername/mic-lock.git
cd mic-lock
./gradlew assembleRelease
```
*Replace `yourusername` with your GitHub username or organization name.*

## 🎯 Quick Start

1.  **Launch the app** and complete the initial permission requests.
2.  **Tap "Start"** to activate microphone protection.
3.  **Test with WhatsApp** or another app by recording a voice message or making a call.
4.  **Enjoy working audio!** The app runs silently in the background, continuously safeguarding your microphone access.

## ⚙️ Settings

-   **MediaRecorder Mode**: (Default) Offers wider compatibility, especially on older or more problematic devices, but might use slightly more battery.
-   **AudioRecord Mode**: More battery-efficient, optimized for most modern devices. If you experience high battery usage, switch to this mode.
-   **Auto-restart**: Ensures the Mic-Lock service automatically restarts if it's ever stopped (e.g., after a device reboot or memory cleanup).

## 🔋 Battery Usage

-   **AudioRecord Mode**: Designed for minimal battery impact, making it ideal for daily use.
-   **MediaRecorder Mode**: Due to its compatibility methods, it might exhibit slightly higher battery consumption. Choose based on your device's behavior and priority.

## 🛠️ Troubleshooting

**Q: The app doesn't seem to work, or audio is still silent.**
A: Try switching between **MediaRecorder Mode** and **AudioRecord Mode** in the app's settings. Some device configurations might prefer one over the other.

**Q: I'm experiencing high battery usage.**
A: Ensure you're using **AudioRecord Mode** in the settings. If the issue persists, verify that battery optimizations are correctly disabled for Mic-Lock.

**Q: The app stops working after a device restart or after some time.**
A: Make sure to disable battery optimization for Mic-Lock as described in the Installation section. Also, enable the **Auto-restart** option in settings.

## 🤝 Contributing

We welcome contributions from the community! Whether it's bug reports, feature suggestions, or code contributions, your help is valuable. Please see [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for full details.

## 📖 Technical Details

For developers and those interested in the technical implementation, architecture, problem-solving approach, and detailed behavioral specifications, refer to the [DEV_SPECS.md](DEV_SPECS.md) file. This document provides deep insights into the app's internal logic and design decisions.

## 🙏 Acknowledgments

-   Thanks to the Android audio system documentation and the open-source community for insights.
-   Inspired by the real-world need to fix persistent microphone hardware issues on **Google Pixel phones** after screen replacements.

---

**Supported Android Versions**: Android 7.0 (API 24) and above  
**Tested Devices**: **Google Pixel 7 Pro** (primary development and testing device). Compatibility with other devices is expected but may require adjustments or specific mode selections (AudioRecord/MediaRecorder).
**Problem Origin**: Screen replacement damage to bottom microphone on Google Pixel devices  
**Solution**: Battery-efficient rerouting to working earpiece microphone