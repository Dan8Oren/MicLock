---
name: Bug report
about: Create a report to help us improve Mic-Lock
title: '[BUG] '
labels: 'bug'
assignees: ''
---

**Describe the bug**
A clear and concise description of what the bug is.

**Device Information**
- Device: [e.g. Google Pixel 7 Pro]
- Android Version: [e.g. Android 14]
- Mic-Lock Version: [e.g. 1.0.0]
- Installation Method: [APK download / Built from source]

**To Reproduce**
Steps to reproduce the behavior:
1. Go to '...'
2. Click on '....'
3. Scroll down to '....'
4. See error

**Expected behavior**
A clear and concise description of what you expected to happen.

**Actual behavior**
A clear and concise description of what actually happened.

**Audio Testing**
- [ ] Tested with WhatsApp voice messages
- [ ] Tested with other recording apps (please specify which ones)
- [ ] Tried switching between MediaRecorder/AudioRecord modes
- [ ] Checked battery optimization settings

**Debug Logs** 🔍
To help us diagnose your issue faster, please consider capturing debug logs:

1. **In the app**: Tap the menu (⋮) → "Debug Tools"
2. **Reproduce the issue**: Use the app normally while the bug occurs
3. **Stop recording**: Tap menu (⋮) → "Stop & Share Debug Logs"
4. **Attach the file**: The app will create a zip file in Downloads/miclock_logs folder

**Why debug logs are helpful:**
- Contains app logs, system audio state, and device information
- Especially useful for device-specific or hard-to-reproduce issues
- Helps identify audio routing problems unique to your device model
- **Privacy-safe**: Only captures MicLock logs (no call audio, contacts, or personal data)

**If you can't use the in-app tool**, you can also provide logs via ADB:
```
Paste logs here
```

**Screenshots**
If applicable, add screenshots to help explain your problem.

**Additional context**
Add any other context about the problem here. For example:
- Did this work before?
- When did the issue start?
- Any recent changes to your device?
- Screen replacement history (important for Pixel devices)