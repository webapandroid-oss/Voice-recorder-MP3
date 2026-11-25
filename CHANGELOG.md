# Changelog

All notable changes to Voice Recorder will be documented in this file.

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
