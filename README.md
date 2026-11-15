# LAN Screen Cast

**English** | [简体中文](README.zh-CN.md)

A free, open-source (GPL-3.0) screen casting app for your local network. It never contacts the internet, shows no ads, needs no account and collects no data.

One APK works as both **sender** and **receiver**: phones and tablets send by default, TVs receive by default.

> **Status: pre-release.** The features below are implemented and covered by unit tests and emulator runs, but **none of them has been verified on real devices yet**. Feature status and the on-device test list are in [`docs/dev/开发进度.md`](docs/dev/开发进度.md) (Chinese).

## Features

**Screen mirroring**
- Low-latency mirroring over WebRTC (H.264), with the phone's playback audio. The microphone is never opened.
- Encryption is always on: mutual TLS 1.3 with certificate pinning. A 4-digit PIN is needed only on the first connection; after that the devices trust each other.
- Several ways to connect: automatic discovery (mDNS/SSDP), QR code, 8-digit cast code, or manual IP.
- Quality presets: Smooth, Balanced, High and **Game** (720p60 with a minimal jitter buffer). Presets can be switched during a session.
- Automatic reconnection, a quick-settings tile, and local cast reports.

**DLNA**
- **Receiver:** choose this device from the "cast" button in video apps.
- **Push to TV:** send local videos, photos, music or a video URL from your phone to a TV's built-in DLNA receiver, then control playback from the phone or the lock screen. Videos the TV can't play are converted first.
- **System share sheet:** share files or links from any app straight to a TV. Recently used TVs also appear directly in the share sheet.

**Compatible with Apple devices (unofficial)**
- iPhone and iPad can connect using the system's Screen Mirroring: screen mirroring, audio, and video URLs. It is off by default and built on [UxPlay](https://github.com/FDH2/UxPlay).

**More**
- **Elder mode:** large text, large buttons, and one tap to cast to a TV that a family member has set up.
- **Reverse control (optional):** the receiver can operate the sending phone. It uses an accessibility service and requires your confirmation for every session.
- **TV-friendly receiver UI:** remote-control navigation and Leanback launcher support.
- **Share the installer:** serve the receiver APK to a TV over the local network.
- **Diagnostics stay local:** network diagnostics and diagnostic logs are kept only on the device.

## Privacy

- No analytics, ads, crash reporting or Google Play services.
- WebRTC uses host candidates only; no STUN or TURN servers.
- The app never connects to the internet. There are two exceptions, both started by you: a URL you push to a TV, and a video URL sent by an Apple device. In both cases the TV or player fetches the URL directly; the app itself does not.

See [`docs/PRIVACY.md`](docs/PRIVACY.md).

## Requirements

- Android 10 (API 29) or later, on phones, tablets and Android TV.
- Sender and receiver must be on the same local network.

## Building

You need the Android SDK (compileSdk 37). Gradle provisions the JDK through its toolchain support. The default build includes the Apple-compatible receiver, which also requires NDK `27.0.12077973`, CMake `3.22.1` and the git submodules.

```bash
git clone https://github.com/binbenc/Android-LANScreenCast.git && cd Android-LANScreenCast
git submodule update --init --depth 1 protocol-impl/airplay/src/main/cpp/third_party

./gradlew assembleDebug          # debug APKs per ABI + universal (app/build/outputs/apk/debug)
./gradlew assembleRelease        # R8-optimized release APKs (unsigned)
./gradlew test                   # JVM unit tests
./gradlew lint

./gradlew assembleDebug -Pwsc.airplay=false   # without the Apple-compatible receiver (no submodules or NDK needed)
```

The first native build cross-compiles OpenSSL and FFmpeg for each ABI, which takes about 2–3 minutes. Later builds skip this step.

## Project layout

```
app/              entry point, navigation, onboarding
core/             protocol, security (TLS/SPAKE2), discovery, signaling, WebRTC, data, UI theme
feature/          sender, receiver, settings, reverse control
protocol-impl/    DLNA renderer and controller, UPnP client, Apple-compatible receiver (JNI)
protocol/         wire protocol spec (PROTOCOL.md) and shared test vectors
docs/             PRD, technical design, interaction design, progress (Chinese)
```

## Documentation

All documents are in Chinese.

- Product requirements: [`docs/prd/PRD-局域网投屏-v1.0.md`](docs/prd/PRD-局域网投屏-v1.0.md)
- Technical design: [`docs/tech/技术方案-v1.0.md`](docs/tech/技术方案-v1.0.md)
- Interaction design and color tokens: [`docs/design/交互稿与色卡-v1.0.html`](docs/design/交互稿与色卡-v1.0.html)
- Wire protocol: [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md)

## Contributing

Issues and pull requests are welcome. Please follow these project rules:

- No new network access.
- New dependencies must be GPL-3.0 compatible.
- No analytics or Google Play services.
- Run `./gradlew test lint` before submitting.

## License

[GPL-3.0](LICENSE).

Third-party components keep their own licenses:

- libwebrtc: BSD-3-Clause
- UxPlay: GPL-3.0
- libplist: LGPL-2.1+
- OpenSSL 3.5: Apache-2.0
- FFmpeg (ALAC decoder only): LGPL-2.1+

Details are in [`protocol-impl/airplay/src/main/cpp/third_party/README.md`](protocol-impl/airplay/src/main/cpp/third_party/README.md) and [`core/rtc/THIRD_PARTY.md`](core/rtc/THIRD_PARTY.md).

This project is not affiliated with or endorsed by Apple Inc. All trademarks belong to their respective owners.
