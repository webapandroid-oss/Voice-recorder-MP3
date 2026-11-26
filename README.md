# Voice-recorder-MP3

<p align="center">
  <strong>A fast, modern, native-MP3 Android voice recorder built with Kotlin, Jetpack Compose, and FFmpeg + LAME.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%E2%80%9336)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 7.0+" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin & Jetpack Compose" />
  <img src="https://img.shields.io/badge/Audio%20Engine-FFmpeg%20%2B%20libmp3lame-007808?style=for-the-badge&logo=ffmpeg&logoColor=white" alt="FFmpeg + LAME" />
  <img src="https://img.shields.io/badge/Design-Material%203-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white" alt="Material Design 3" />
</p>

**Voice-recorder-MP3** captures microphone audio and encodes it directly to real `.mp3` files in real time—no bulky WAV intermediates, no post-recording waiting, and no extra clutter. Designed with a clean studio-inspired Material Design 3 interface, it gives you live waveform metering, configurable bitrates up to 320 kbps, background recording controls, and an inline MP3 player all in one lightweight app.

---

## ✨ Features

- **Native Real-Time MP3 Encoding:** Powered by a custom minimal `libffmpeg.so` (FFmpeg 6.1.2 + LAME 3.100) compiled for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86` with 16 KB page-size support.
- **Live Studio Waveform & dBFS VU Meter:** Watch a responsive 56-bar real-time waveform, 28-segment color-coded VU meter, tenth-of-a-second precision timer (`MM:SS.d`), and live encoded file size while recording.
- **Configurable MP3 Quality:** Customize output quality before recording from **⋮ → Quality Settings** with live storage estimates (`~MB/min`):
  - **Bitrates:** `96 kbps` (Compact Voice), `128 kbps` (Standard MP3), `192 kbps` (High Fidelity), `320 kbps` (Studio Master)
  - **Sample Rates:** `22.05 kHz`, `44.1 kHz`, `48 kHz`
  - **Channels:** `Mono` or `Stereo`
- **Background Recording Service:** Uses an Android Foreground Service (`microphone`) so recording continues smoothly when you lock your screen or switch apps, complete with **Pause / Resume** and **Save MP3** notification controls.
- **Pause, Resume & Safe Discard:** Pause and continue within the same MP3 file seamlessly, or discard unwanted takes (with a confirmation prompt for recordings of 3 seconds or longer).
- **Custom Recording Titles:** Set a custom filename before hitting record, or let the app auto-name files with a timestamp.
- **Recordings Library & File Management:**
  - Instant search by recording title or filename
  - Quick sorting by **Newest**, **Oldest**, **Longest**, or **Largest**
  - **Rename**, **Share**, **Save (Export)** to device storage, and **Delete**
- **Audio-to-MP3 Converter:** Convert compatible audio files (`.mp3`, `.wav`, raw PCM) directly inside the **Recordings** tab using your active MP3 quality settings.
- **Built-In Inline MP3 Player:** Play recordings directly inside the library cards with a seek slider, `-10s` / `+10s` skip buttons, and playback speed control (`0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`).
- **Adaptive Phone & Tablet Layout:** Automatically switches from a bottom navigation bar on phones to a side-by-side **Record + Recordings** split view on large screens (`>= 840dp`).
- **In-App Release Updater:** Checks for new GitHub Releases, verifies APK SHA-256 checksums and signing compatibility, shows release notes, and installs updates in-app.

### MP3 Quality Presets at a Glance

| Preset | Bitrate | Approximate Size | Best For |
| :--- | :--- | :--- | :--- |
| **Compact Voice** | `96 kbps` | ~0.72 MB/min | Long lectures, voice memos, minimal storage |
| **Standard MP3** | `128 kbps` | ~0.96 MB/min | Everyday notes and meetings |
| **High Fidelity** *(Default)* | `192 kbps` | ~1.44 MB/min | Clear interviews, podcasts, vocal recordings |
| **Studio Master** | `320 kbps` | ~2.40 MB/min | Maximum MP3 fidelity |

---

## 🎙️ How to Record

1. Open the app on the **Record** tab.
2. *(Optional)* Tap **⋮ (More Options)** in the top-right corner → **Quality Settings** to pick your **Bitrate**, **Sample Rate**, and **Mono / Stereo** channel mode.
3. *(Optional)* Enter a custom name in the **Title** field before starting.
4. Tap the large circular **Microphone** button at the bottom center.
   - Allow **Microphone** permission when prompted.
   - On Android 13+, allow **Notifications** permission so live recording controls appear in your notification shade.
5. While recording:
   - Tap the center **Pause** button anytime to pause, and **Play** to resume.
   - Tap the **✕ (Discard)** button on the left to cancel the recording without saving it.

---

## 💾 How to Save

### 1. Finish & Save a Recording
- While recording or paused, tap the **Stop (`■`)** button on the right side of the control deck—or tap **Save MP3** directly on the ongoing recording notification.
- The app flushes the MP3 encoder and saves your `.mp3` file into the **Recordings** library.

### 2. Save (Export) to Your Device's Shared Storage
New recordings are initially stored inside the app's private library. To copy an MP3 to your phone's shared storage for external music players or file managers:
1. Open the **Recordings** tab.
2. Tap **⋮** on the recording card you want to export.
3. Tap **Save**.
   - **Android 10 (API 29) & newer:** Saves directly to `Music/Voice Recorder/<filename>.mp3` via `MediaStore` (no storage permission required).
   - **Android 7.0 – 9 (API 24–28):** Saves to the app's external `Music` directory.

---

## 🎧 How to Listen to Recordings

1. Open the **Recordings** tab (or view the right panel on tablets/foldables).
2. Browse, search by name, or sort using **Newest**, **Oldest**, **Longest**, or **Largest**.
3. Tap the **Play (`▶`)** button on any recording card to start listening.
4. Use the inline player controls that expand inside the card:
   - **Play / Pause (`▶` / `⏸`)** to pause or resume.
   - **Progress Slider** to jump to any timestamp.
   - **`-10s` / `+10s`** buttons to skip backward or forward by 10 seconds.
   - **Speed Button (`1.0x`)** to cycle through `0.75x`, `1.0x`, `1.25x`, `1.5x`, and `2.0x`.

---

## ⚠️ Warnings & Known Limitations

Please keep the following caveats and potential issues in mind before using the app:

- **Audio-to-MP3 Converter Format Limit:** Although the system file picker lets you browse general audio files, the bundled `libffmpeg.so` is intentionally built as an MP3-only library supporting **MP3, WAV, and raw PCM** (`pcm_s16le`, `pcm_s24le`, `pcm_s32le`, `pcm_f32le`, `pcm_u8`) inputs. Attempting to convert `.m4a`, `.aac`, `.ogg`, `.flac`, or `.opus` files will fail.
- **Uninstalling Deletes Unexported Recordings:** All recordings are saved in the app's private internal storage (`files/recordings`) by default. Uninstalling the app or clearing app data will **permanently erase** any recordings that you have not exported via **Save** or **Share**.
- **Android 7–9 Export Location:** On Android 7.0–9 (API 24–28), the **Save** action writes to the app-scoped external Music directory (`Android/data/.../files/Music`), which Android also removes upon uninstall—use **Share** if you want to move files elsewhere permanently on older devices.
- **Quality Settings Are Locked Mid-Recording:** You cannot change bitrate, sample rate, or channel mode while a recording is active or paused. Stop or discard the active session first.
- **Hardware Microphone Compatibility:** Some Android devices do not support **Stereo** microphone capture or certain sample rates (such as `22.05 kHz` or `48 kHz`) in `AudioRecord`. If microphone initialization fails, switch back to **Mono** at **44.1 kHz**.
- **Battery Optimization & Background Interruptions:** Aggressive manufacturer battery savers (such as MIUI, ColorOS, FunTouch OS, or One UI background limits) may stop long background recordings or delay background update checks unless battery optimization is disabled for the app.
- **Microphone & Notification Permissions:** Denying microphone access blocks recording until granted in system Settings. Denying notifications on Android 13+ hides the ongoing pause/save notification controls and update alerts.
- **In-App Update Permissions & Signing Key Mismatch:**
  - Installing updates directly from inside the app requires granting **"Install unknown apps"** permission for Voice Recorder.
  - If you switch between a locally built **debug** APK and a signed **release** APK, Android will fail to install over the existing app due to a signing certificate mismatch—export your recordings first, uninstall the old build, then install the new APK.
- **Storage Space & Data Usage:** High-bitrate recordings (especially `320 kbps` at ~2.4 MB/min) require adequate free storage; running out of space mid-recording can cause saving to fail. Automatic background update checks also require internet access.
