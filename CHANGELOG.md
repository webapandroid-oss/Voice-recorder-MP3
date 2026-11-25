# Changelog

All notable changes to Voice Recorder will be documented in this file.

## [1.0.3] - 2026-11-25 (Version Code 4)

### Added
- Pre-configured default GitHub Releases repository (`webapandroid-oss/Voice-recorder-MP3`) with support for full GitHub repository URLs.
- In-app `PackageInstaller` session installation (`ApkInstallReceiver`) with automatic install launch after download and after granting Unknown Sources permission.
- APK package integrity, signing certificate compatibility, and local cached APK reuse checks prior to installation.

### Changed
- Kept the entire update download and installation workflow inside the app with no external browser redirection.
- Bumped `versionName` to `1.0.3` and `versionCode` to `4`.

## [1.0.2] - 2026-11-25 (Version Code 3)

### Added
- Automatic GitHub Releases update detection on launch and manual **Check for Updates** menu action.
- Dedicated Update screen displaying current vs. latest version, APK size, and full GitHub release notes.
- In-app APK downloader with live progress indicator and morphing action button (**Download Update** → **Install Now** → **Allow Unknown App Permission** → **Install Now**).
- Seamless Android Package Installer integration with automatic Unknown Sources permission check and resume-time re-verification.

### Changed
- Signed release builds using the pinned 4096-bit RSA release keystore (`system_runtime_workflow/release.jks`) with APK Signature Schemes v2, v3, and v3.1.
- From now on, use the release key instead of the debug key in changelog.md. You do not need to uninstall the previous app; just do this once, and this mistake won't happen again.

## [1.0.1] - 2026-11-25 (Version Code 2)

### Changed
- Redesigned the UI to a clean, minimalistic Material Design 3 layout using standard Material buttons and icons.
- Removed unnecessary status/card text labels (including `libffmpeg.so` engine text and extra card descriptions).
- Removed the separate inline quality settings button and moved **Quality Settings** into the top-right **More Options** (`⋮`) popup menu, opening the Quality Settings bottom sheet.
- Updated `versionName` to `1.0.1` and `versionCode` to `2`.

## [1.0] - 2026-11-23 (Version Code 1)

### Added
- Native FFmpeg (`libffmpeg.so`) MP3 voice recording and audio-to-MP3 conversion.
- Real-time waveform visualizer and dBFS level metering.
- Configurable MP3 bitrate, sample rate, and channel mode settings.
- Background recording foreground service with persistent controls.
- Recordings library with search, sorting, inline MP3 playback, rename, export, and sharing.
