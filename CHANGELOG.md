# Changelog

All notable changes to Voice Recorder will be documented in this file.

## [1.0.6.1] - 2026-11-27 (Version Code 8)

### Changed
- **Record Screen & Active Recording Controls Redesign:** Upgraded the active recording transport layout with an elevated 3-button Studio Transport Dock (`Discard`, `Pause`/`Resume`, and high-contrast `Save`) featuring larger touch targets, clear action labels, tactile haptic feedback, and a live status badge (`REC` / `PAUSED` / `MP3`).
- **Faster Startup & Smoother UI Performance:** Moved recording file target creation off the main thread onto `Dispatchers.IO`, eliminated top-level Compose recompositions during active recording ticks, optimized waveform Canvas rendering to zero per-frame list allocations, and added SQL filtering to MediaStore queries.
- **Durable & Responsive UX:** Added tabular timecode numerals (`tnum`) to prevent timer jitter, quick-clear title button, adaptive waveform height on compact screens, and kept all `1.0.6` features intact.
- **Bumped App Version:** Updated `versionName` to `1.0.6.1` and `versionCode` to `8`.

## [1.0.5] - 2026-11-26 (Version Code 6)

### Changed
- **Default English Recording Filename Format:** Updated default recording filenames to `Recording_dd_MM_yyyy_HH_mm_ss.mp3` (with `"Recording"` in English before the timestamp).
- **Direct Internal Storage Saving (No Cache):** Recordings are now encoded and saved directly to `/storage/emulated/0/Music/Voice Recorder` via `MediaStore` (and direct public `Music/Voice Recorder` on Android 7–9) without using temporary or private cache files.
- **Bumped App Version:** Updated `versionName` to `1.0.5` and `versionCode` to `6`.

### Added
- **Direct SD Card Saving (`Music/Voice Recorder`):** Added support for saving MP3 recordings directly to `Music/Voice Recorder` on an inserted SD card.
- **SD Card Launch Prompt:** Automatically displays a `"Save recordings to SD cards?"` dialog with **Cancel** and **Change** buttons when opening the app with an SD card inserted while using Internal Storage.
- **More Options Storage Switcher:** Added **"Change storage location to Internal Storage"** and **"Change storage location to SD card"** options in the top-right **More Options (`⋮`)** menu whenever an SD card is available.

## [1.0.4] - 2026-11-26 (Version Code 5)

### Fixed
- **Eliminated GitHub REST API Rate Limit Completely:** Replaced `api.github.com` runtime polling with a dedicated rate-limit-free `update-manifest.json` feed published directly by the APK build workflow (`raw.githubusercontent.com/.../update-feed/update-manifest.json` + GitHub Release `update-manifest.json` asset), backed by `releases.atom` and `/releases/expanded_assets/{tag}` fallbacks.
- **Conditional HTTP & Local Metadata Caching:** Added `ETag` / `Last-Modified` (`304 Not Modified`) headers and persistent release metadata caching so repeated 1-minute checks transfer near-zero payload without ever hitting API quotas.

### Added
- **1-Minute Continuous Update Polling & Instant In-App Banner:** Updated the APK build workflow to generate and publish `update-manifest.json` (with exact APK size and SHA-256 checksum) on every build, configured 1-minute update polling inside the app and chained `JobScheduler` background checks, and added an immediate in-app **Update Available** banner and automatic update prompt as soon as a new release is detected.

### Security
- **Locked Official Repository (`webapandroid-oss/Voice-recorder-MP3`):** Permanently pinned all update checks and APK downloads to `webapandroid-oss/Voice-recorder-MP3` and removed repository configuration/editing UI and preferences to prevent malicious repository hijacking or spoofed app updates.
- **Strict Download & Package Integrity Verification:** Enforced HTTPS-only GitHub/CDN host allowlists, SHA-256 APK checksum verification, exact package-name validation, anti-downgrade `versionCode` checks, and signing certificate verification on release builds before in-app installation.

### Changed
- Bumped semantic bugfix version to `1.0.4` and `versionCode` to `5`.

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
