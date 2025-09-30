# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2025-01-24

### Added
- **Intelligent Quick Settings Tile**: A new state-aware Quick Settings tile that provides at-a-glance status (On, Off, Paused) and one-tap control of the MicLock service.
- **Robust Service Start from Tile**: Implemented a multi-layered fallback system to ensure the service can be reliably started from the Quick Settings tile, even on modern Android versions with background start restrictions. If a direct start fails, the app briefly opens to securely start the service and then closes.

### Enhanced
- The tile now displays a "Paused" state when another app is using the microphone, providing clearer feedback to the user.
- The tile shows an unavailable state with a "No Permission" label if required permissions have not been granted.

## [1.0.1] - 2025-01-23

### Enhanced
- **Improved Service Reliability (Always-On Foreground Service):** The MicLockService now remains in the foreground at all times when active, even when the screen is off. This significantly improves service reliability by preventing the Android system from terminating the background process.
  - When the screen turns off, microphone usage is automatically paused to conserve battery
  - The service notification is updated to display "Paused (Screen off)" to clearly communicate its status
  - When the screen turns on, microphone holding automatically resumes

### Fixed
- Addressed issues where the service could be terminated by aggressive OEM power management when the screen was off

## [1.0.0] - 2024-01-20

### Added
- Initial public release of Mic-Lock
- Core functionality to reroute audio from faulty bottom microphone to earpiece microphone on Google Pixel devices
- Battery-efficient background service with dual recording strategy (MediaRecorder/AudioRecord modes)
- Polite background holding mechanism that yields to foreground applications
- Route validation and automatic fallback between recording methods
- Configurable compatibility modes for different device behaviors
- Auto-restart functionality to maintain service after device reboots
- Battery optimization exemption request for uninterrupted operation
- Comprehensive UI with real-time status updates and mode selection
- Android 14+ foreground service restriction compatibility
- Screen state integration for optimized power management

### Technical Features
- Multi-channel audio route preference targeting primary microphone arrays
- Dynamic route validation with microphone position detection
- Exponential backoff strategy for polite microphone re-acquisition
- Enhanced error handling and recovery mechanisms
- Comprehensive logging and debugging capabilities

### Tested
- Confirmed working on Google Pixel 7 Pro
- Validated against screen replacement damage scenarios
- Battery efficiency testing across different usage patterns

### Documentation
- Complete technical specification (DEV_SPECS.md)
- User-friendly installation and usage guide
- Contributing guidelines for open source development
- Issue templates for bug reports and feature requests

[1.1.0]: https://github.com/yourusername/mic-lock/releases/tag/v1.1.0
[1.0.0]: https://github.com/yourusername/mic-lock/releases/tag/v1.0.0