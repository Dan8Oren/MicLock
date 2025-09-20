# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.0.0]: https://github.com/yourusername/mic-lock/releases/tag/v1.0.0