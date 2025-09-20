# Troubleshooting Guide for Mic-Lock

This guide provides solutions to common problems you might encounter with Mic-Lock.

## 🚨 Quick Fixes

### App doesn't seem to work / Audio is still silent

**Most Common Solution**: Switch recording modes
1. Open Mic-Lock settings
2. Try switching between **MediaRecorder Mode** and **AudioRecord Mode**
3. Stop and restart the service after changing modes
4. Test again with WhatsApp or another recording app

### High battery usage

1. **Switch to AudioRecord Mode** in Mic-Lock settings
2. **Verify battery optimization** is disabled (see [Setup Guide](setup.md#3-disable-battery-optimizations-critical))
3. **Restart the device** after making changes

### App stops working after device restart

1. **Check battery optimization settings** - they may have been reset
2. **Manually restart Mic-Lock** after reboot
3. **Enable auto-start** in your device's app management settings

## 📱 Device-Specific Issues

### Google Pixel Devices

**Issue**: Mic-Lock designed for Pixel devices but still not working
- **Solution**: Ensure you're using the correct recording mode for your specific Pixel model
- **Note**: Pixel 7 Pro is confirmed working; other Pixel models may need different settings

**Issue**: Audio works sometimes but not always
- **Solution**: This may indicate the bottom microphone is intermittently functional
- **Action**: Try different recording modes and report results

### Samsung Galaxy Devices

**Issue**: App keeps getting killed by system
- **Solution**: Samsung has aggressive battery management
- **Actions**:
  1. Settings → Device care → Battery → App power management → Apps that won't be put to sleep → Add Mic-Lock
  2. Settings → Apps → Mic-Lock → Battery → Optimize battery usage → Turn off
  3. Disable "Adaptive battery" for Mic-Lock

### OnePlus Devices

**Issue**: Service stops after screen off
- **Solution**: OnePlus has strict background app management
- **Actions**:
  1. Settings → Apps → Mic-Lock → Battery → Battery optimization → Don't optimize
  2. Settings → Apps → Mic-Lock → App launch → Manage manually → Enable all options

### Xiaomi/MIUI Devices

**Issue**: App doesn't start automatically
- **Solution**: MIUI requires explicit autostart permission
- **Actions**:
  1. Settings → Apps → Manage apps → Mic-Lock → Autostart → Enable
  2. Settings → Apps → Manage apps → Mic-Lock → Battery saver → No restrictions
  3. Security app → Permissions → Autostart → Enable for Mic-Lock

## 🔧 Technical Troubleshooting

### Audio Route Issues

**Problem**: App starts but audio routing doesn't work

**Diagnosis Steps**:
1. Check if Mic-Lock status shows "ON" (not "PAUSED")
2. Try recording with different apps (WhatsApp, Voice Recorder, etc.)
3. Test both recording modes
4. Restart the device and try again

**Advanced Solutions**:
- Clear app cache: Settings → Apps → Mic-Lock → Storage → Clear Cache
- Reset app data (will lose settings): Settings → Apps → Mic-Lock → Storage → Clear Data

### App Crashes or Won't Start

**Check Requirements**:
- Android 7.0 (API 24) or higher
- Microphone permission granted
- Notification permission granted

**Solutions**:
1. **Reinstall the app**: Uninstall and reinstall Mic-Lock
2. **Check APK integrity**: Re-download from official source
3. **Free up storage**: Ensure device has adequate free space
4. **Restart device**: Simple reboot can resolve many issues

### Compatibility with Other Apps

**Issue**: Specific apps still don't work with Mic-Lock

**Troubleshooting**:
1. **Test app order**: Start Mic-Lock first, then the recording app
2. **Check app permissions**: Ensure the recording app has microphone permission
3. **Try different recording mode**: Switch between MediaRecorder/AudioRecord
4. **Report compatibility**: Use our [Device Compatibility Report](https://github.com/yourusername/mic-lock/issues/new?template=device_compatibility.md)

## 🔍 Advanced Diagnostics

### Check Service Status

1. Open Mic-Lock app
2. Observe the status display:
   - **"ON"**: Service running and holding microphone
   - **"PAUSED"**: Service running but yielding to another app (normal)
   - **"OFF"**: Service not running (problem)

### Log Analysis (Advanced Users)

If you can access Android logs via ADB:

```bash
adb logcat | grep -i "miclock\|mic_lock"
```

Look for:
- Permission errors
- Audio system errors
- Service lifecycle issues

### Test Without Mic-Lock

1. Stop Mic-Lock service
2. Test recording apps to confirm the original problem exists
3. Start Mic-Lock and test again to verify it's working

## 📞 When to Seek Help

### Create a Bug Report

Use our [Bug Report template](https://github.com/yourusername/mic-lock/issues/new?template=bug_report.md) if:
- You've tried all troubleshooting steps
- The issue persists across device reboots
- You can reproduce the problem consistently

### Include This Information

1. **Device details**: Model, Android version, manufacturer
2. **Mic-Lock version**: Found in app settings
3. **Steps taken**: What troubleshooting you've already tried
4. **Specific symptoms**: Exact behavior you're experiencing
5. **Hardware context**: Any screen replacements or damage

### Device Compatibility Report

If Mic-Lock works (or doesn't work) on an untested device, please submit a [Device Compatibility Report](https://github.com/yourusername/mic-lock/issues/new?template=device_compatibility.md) to help other users.

## 💡 Tips for Best Results

1. **Test immediately after setup** to ensure everything works
2. **Monitor battery usage** for the first few days
3. **Keep the app updated** when new versions are available
4. **Report working configurations** to help other users
5. **Be patient** - some device configurations may need fine-tuning

## ❓ Still Need Help?

- Check existing [GitHub Issues](https://github.com/yourusername/mic-lock/issues)
- Review the [Technical Documentation](../DEV_SPECS.md) for advanced details
- Join community discussions in the Issues section

Remember: Mic-Lock addresses a specific hardware problem (damaged bottom microphone), so it may not solve all audio issues on all devices.