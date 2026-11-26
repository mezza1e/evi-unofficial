# 🎵 Evi Unofficial (Android)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20%28API%2026%2B%29-brightgreen.svg)](https://developer.android.com)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)](.github/workflows/build-and-release.yml)
[![Security Scan](https://img.shields.io/badge/CodeQL-Protected-blue.svg)](.github/workflows/codeql.yml)
[![VirusTotal](https://img.shields.io/badge/VirusTotal-0%2F70%20Clean-success.svg)](https://www.virustotal.com)

A modern, fast, native Android music client for [evi.wtf](https://evi.wtf), built with **Jetpack Compose**, **Kotlin Coroutines**, and **AndroidX Media3 ExoPlayer**.

---

## 🔒 Security & Privacy Guarantee

Because Evi Unofficial is an unofficial client, security and transparency are paramount. This application is designed to be 100% open, privacy-respecting, and verifiable:

* **Zero Telemetry / No Trackers**: No Firebase Analytics, Google Analytics, Sentry, Facebook SDK, Adjust, or any third-party tracking scripts.
* **100% Ad-Free**: No advertising SDKs, sponsored links, or tracking cookies.
* **Strict HTTPS Only**: Cleartext HTTP traffic is disabled at the OS level via `android:usesCleartextTraffic="false"`. All communication occurs exclusively over encrypted TLS connections to the official `https://evi.wtf` backend.
* **Minimal Permission Footprint**: We do **not** request access to your Location, Contacts, Camera, Microphone, SMS, or private device storage.
* **Open Source & Verifiable**: All releases can be reproduced and built directly from the source code via public GitHub Actions.

### Android Permissions Breakdown

| Permission | Purpose |
| :--- | :--- |
| `android.permission.INTERNET` | Streams audio chunks and downloads album artwork exclusively from `evi.wtf`. |
| `android.permission.ACCESS_NETWORK_STATE` | Checks network connectivity to optimize media buffers and switch to offline cached playback when disconnected. |
| `android.permission.WAKE_LOCK` | Prevents the CPU from sleeping mid-song while audio is actively playing. |
| `android.permission.FOREGROUND_SERVICE` | Required by Android for foreground audio playback services. |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Required by Android 14+ to declare a media playback foreground service. |
| `android.permission.POST_NOTIFICATIONS` | Displays playback notification and lock-screen media controls (Android 13+). |
| `android.permission.MODIFY_AUDIO_SETTINGS` | Required to route audio through the built-in Equalizer, Bass Boost, and Virtualizer effects. |

---

## 🛡️ Verifying Release Authenticity

Every release published in [GitHub Releases](../../releases) includes cryptographic SHA-256 checksums to prove the binary has not been modified or tampered with.

### Latest Release: v1.0.0
* **File**: `Evi-Unofficial-1.0.0.apk`
* **SHA-256**: `5E33C96C62CECA5C83053F0C4D5ADB7286F8FF13F5BA8F86104DCCE921221A9A`

#### Verify on Windows (PowerShell):
```powershell
Get-FileHash Evi-Unofficial-1.0.0.apk -Algorithm SHA256
```

#### Verify on Linux / macOS:
```bash
sha256sum Evi-Unofficial-1.0.0.apk
```

You can also submit the APK directly to [VirusTotal.com](https://www.virustotal.com) to verify that 70+ antivirus engines report 0 detections.

---

## ✨ Features

- **Full Playback Engine**: Powered by AndroidX Media3 ExoPlayer with gapless playback, cache pre-buffering, and seamless repeat/shuffle.
- **Hardware-Calibrated Audio Equalizer**: 5-band frequency tuning, native device DSP presets, dedicated Bass Boost, and Surround Virtualizer.
- **Dynamic Theming & Accent Colors**: Dark UI with custom themes (Evi Black, Graphite, Midnight, Plum, Forest) and accent highlights.
- **Synchronized Lyrics**: Real-time karaoke-style word-by-word synced lyrics.
- **Offline Caching & Downloads**: Store favorite songs and album artwork locally for offline listening.
- **Autoplay Radio**: Smart playlist infinite radio mode with strict loop-boundary protection.
- **Social Feed & Presence**: Discord activity presence and friend listening status.

---

## 🛠️ Building from Source

Anyone can verify and compile the APK independently:

### Prerequisites
* Java Development Kit (JDK) 17+
* Android SDK (API 34)

### Build Steps
```bash
# 1. Clone the repository
git clone https://github.com/<your-username>/evi-mobile.git
cd evi-mobile

# 2. Run unit and stress tests
./gradlew testDebugUnitTest

# 3. Assemble release APK
./gradlew assembleRelease
```
The compiled, signed release APK will be generated at:
`app/build/outputs/apk/release/Evi-Unofficial-1.0.0.apk`

---

## 📄 License

This project is licensed under the GNU General Public License v3.0 (GPLv3). See the [LICENSE](LICENSE) file for details.

---

## 📬 Security Disclosures

If you discover a potential security or privacy issue, please review our [Security Policy](SECURITY.md) to disclose it responsibly.
