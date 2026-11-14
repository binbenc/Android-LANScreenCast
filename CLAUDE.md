# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

WirelessScreenCasting (package `com.benchan.wireless.screencasting`, placeholder product name “局域投屏”) is a GPL-3.0, zero-internet LAN screen-casting app: one APK with sender/receiver dual mode (phones default to sender, TVs to receiver). The M1 MVP code is in place (WSC mirroring over WebRTC, pairing, discovery, DLNA DMR receiver, Compose / Compose for TV UI, local report & diagnostics, LAN APK sharing); the Apple-compatible receiver (F15: UxPlay via JNI — mirroring, RAOP audio, video URL/HLS) is implemented and cross-compiles behind a build switch. **Nothing has been verified on real devices yet.** Feature-by-feature status, deviations from the docs and the on-device test list live in `docs/dev/开发进度.md` — keep it updated.

Specs (Chinese): `docs/prd/PRD-局域网投屏-v1.0.md` (content is v1.1; decisions #1–#20 in section 16 are locked), `docs/tech/技术方案-v1.0.md`, `docs/design/交互稿与色卡-v1.0.html` (color tokens at the top of the file; `core/ui/.../theme/Color.kt` is generated from them). Wire protocol: `protocol/PROTOCOL.md` + shared test vectors in `protocol/vectors/`.

Hard rules from the PRD: no analytics/ads/crash-reporting/Google Play Services dependencies; the app must not contact the internet (WebRTC has no STUN/TURN, host candidates only; the only exceptions are a URL a user pushes via DLNA and an Apple device's video URL (HLS segments)); new dependencies must be GPL-3.0 compatible; UI text never says “AirPlay” (use “兼容 Apple 设备（非官方）”).

## Commands

```bash
G='./gradlew -Dhttp.nonProxyHosts=* -Dhttps.nonProxyHosts=*'   # see "Local environment" below
$G assembleDebug                 # debug APKs per ABI + universal (app/build/outputs/apk/debug)
$G assembleRelease               # R8-optimized release APKs (unsigned)
$G test                          # all JVM unit tests (JVM modules: `test`; Android modules: testDebugUnitTest/testReleaseUnitTest)
$G lint                          # Android lint for every module (abortOnError = true)
$G installDebug                  # install on a connected device

# Single module / class / method
$G :core:protocol:test                                   # pure-Kotlin JVM module
$G :core:protocol:test --tests '*Spake2Test'
$G :core:security:testDebugUnitTest --tests 'com.benchan.wireless.screencasting.core.security.SecurityTest.mutualTlsWithPinning'
$G :protocol:dlna:testDebugUnitTest                      # Gradle path :protocol:* maps to protocol-impl/*
$G :feature:sender:compileDebugKotlin                    # fast compile check of one module

# Apple-compatible receiver (UxPlay via JNI) – OFF by default
git submodule update --init --depth 1 protocol-impl/airplay/src/main/cpp/third_party   # once: UxPlay, libplist, OpenSSL 3.5, FFmpeg
$G assembleDebug                             # AirPlay is ON by default; also runs :protocol:airplay:buildNativeDeps (first time ~2.5 min)
$G assembleDebug -Pwsc.airplay=false        # build without AirPlay (no submodules/NDK needed)
$G -Pwsc.airplay=true :protocol:airplay:assembleDebugAndroidTest   # device self-test (UxPlayNativeDeviceTest)
protocol-impl/airplay/scripts/build-native-deps.sh --host-selftest  # after changing OpenSSL options: host algorithm check
```

`wsc.airplay` (default `true` in `gradle.properties`, per PRD decision #3; pass `-Pwsc.airplay=false` to exclude) makes `:app` depend on `:protocol:airplay`, turns on its CMake build (NDK 27.0.12077973, CMake 3.22.1) and sets `BuildConfig.AIRPLAY_AVAILABLE`. Before CMake configures, the Exec task `:protocol:airplay:buildNativeDeps` runs `protocol-impl/airplay/scripts/build-native-deps.sh`, which cross-compiles OpenSSL (libcrypto only, many algorithms disabled via `OPENSSL_OPTIONS`) and FFmpeg (ALAC decoder only) per ABI into `protocol-impl/airplay/native-deps/<abi>/` (git-ignored; override with `-Pwsc.airplay.depsDir=`). The script is incremental via a stamp (submodule commits + script hash + NDK revision), so the task always runs but skips in seconds; missing submodules/NDK fail with an explicit message. libplist and UxPlay `lib/` are compiled directly by `src/main/cpp/CMakeLists.txt` (which also applies one build-time patch to a UxPlay `exit(1)` without touching the submodule). The `.so` exports only `JNI_OnLoad`, uses `ANDROID_STL=none` (pure C) and 16 KB pages. Without the switch, `Optional<AirPlayReceiver>` is empty and the settings toggle is hidden; the module's Kotlin code and unit tests still compile/run in the default build. Third-party pins and licenses: `protocol-impl/airplay/src/main/cpp/third_party/README.md`.

## Architecture

```
build-logic/convention   # convention plugins: wsc.android.application / .library / .compose, wsc.jvm.library, wsc.hilt
app                      # MainActivity (Compose NavHost, onboarding, mode choice), WscApplication (log sinks), keepRules/
core/common   (JVM)      # WscLog facade, Clock, Hex/Base64Url, Ids
core/protocol (JVM)      # WSC messages + codec, SessionStateMachine, ReconnectBackoff, SPAKE2 (RFC 9382), CastCode, QrPayload,
                         #   PairingGuard, QualityProfile/CaptureSize/LatencyGrade/AutoQualityPolicy, DataChannel messages
core/data                # DataStore settings, recent devices, session report store + summary, ring-buffer DiagnosticLog
core/security            # device identity (P-256 self-signed cert, key wrapped by AndroidKeyStore), TLS 1.3 contexts,
                         #   fingerprint pinning TrustManager, TrustedDeviceStore, SenderPairing/ReceiverPairing
core/signaling           # WscServer (Java-WebSocket, WSS, client cert required), WscClient (OkHttp, pinned)
core/net                 # NetworkMonitor (LAN IPv4/prefix, no SSID), MulticastLockHolder, LanHttpServer (Ktor CIO), SSDP codec/socket
core/discovery           # NsdAdvertiser/NsdBrowser (_wsc._tcp), SsdpSearcher, DeviceDiscovery (mDNS+SSDP+recent IP), QR matrix/decoder, NetworkDiagnostics
core/rtc                 # libwebrtc wrapper: RtcEngine/RtcConfig (host-only ICE), SenderRtcSession (ScreenCapturerAndroid, FrameRepeater,
                         #   PlaybackAudioCapture, QualityApplier), ReceiverRtcSession, StatsParser/LatencyEstimator, SdpMunger
core/ui                  # theme (tokens → M3 + Compose for TV), StatusCapsule, PIN cells/keypad, QrCode, charts, TvButton/TvCard
feature/sender           # SenderController (session orchestration, pairing, reconnect, report), ScreenCastService (mediaProjection FGS),
                         #   CastTileService, screens 01–08 (device list, PIN, cast code, scanner, intro sheet, casting, reconnect/ended)
feature/receiver         # ReceiverController (listener, mDNS, cast code/QR, pairing, RTC answer, resume, DLNA/Apple integration),
                         #   ConsentBroker (one confirm dialog for WSC/DLNA, R-09), ReceiverService (mediaPlayback FGS), BootReceiver, TV screens 11–15
feature/settings         # settings 09A/09B, trusted devices, report 10, network diagnostics, log export, F26 APK sharing
protocol-impl/dlna       # :protocol:dlna – self-built DMR: SSDP advertiser, SOAP actions, GENA, Ktor routes, Media3 ExoPlayer
protocol-impl/airplay-api# :protocol:airplay-api – AirPlayReceiver interface + @BindsOptionalOf
protocol-impl/airplay    # :protocol:airplay – UxPlay via JNI: C bridge (uxplay_jni.c, android_dnssd.c → NsdManager, alac_decoder.c → FFmpeg),
                         #   UxPlayAirPlayReceiver (session state, PIN, admission), MirrorVideoDecoder (MediaCodec → Surface),
                         #   RaopAudioPlayer (ALAC PCM / AAC MediaCodec → AudioTrack), HlsVideoPlayer (UxPlay local playlist → ExoPlayer)
protocol/                # PROTOCOL.md + vectors (spake2, messages, state) shared with the future TS implementation
```

Key flows: sender `SenderController` drives `SenderState` (Idle → Connecting → AwaitingPin/AwaitingTvConfirm → ReadyToCapture → Starting → Casting ⇄ Reconnecting → Ended). The projection result goes straight to `ScreenCastService`, which calls `startForeground(MEDIA_PROJECTION)` **before** the RTC session calls `getMediaProjection()` (Android 14+ rule); the capturer is started once per session and reconnects only do ICE restart. Receiver listening runs only while its screen is in the foreground (ref-counted `startListening/stopListening`); playback is kept alive by `ReceiverService`. Both controllers serialize state changes on a single-threaded dispatcher and gate every signaling message through `SessionStateMachine` (SDP/ICE before authentication ⇒ `ERROR{UNAUTHENTICATED}` + disconnect).

## Build setup (non-obvious bits)

- **Toolchain**: AGP 9.4.1, Gradle 9.6.0, daemon JVM 25 (foojay), compileSdk/targetSdk 37, **minSdk 29**, Java/Kotlin **17** bytecode — all set by the convention plugins in `build-logic`, not per module.
- **AGP 9 built-in Kotlin (KGP 2.2.10)**: never apply `org.jetbrains.kotlin.android`. Android modules get Kotlin from AGP; JVM modules use `org.jetbrains.kotlin.jvm` 2.2.10 (same version as the built-in KGP); Compose needs only `org.jetbrains.kotlin.plugin.compose`. All plugins are declared `apply false` in the root `build.gradle.kts`; `build-logic` depends on them `compileOnly` to avoid duplicate class loaders.
- **Hilt 2.60.1 + KSP 2.3.12** via `wsc.hilt`. Library manifests declare the permissions their code uses (lint `MissingPermission`).
- **New AGP DSL**: release uses `optimization { enable = true }` with the default `packageScope` (`**`, whole app incl. Ktor/BouncyCastle/icons). R8 keep rules live in `app/src/main/keepRules/*.keep` (`webrtc.keep`, `jni.keep` — incl. `UxPlayCallbacks`/`UxPlayNative` looked up by name from C, `serialization.keep`, `network.keep`, `rules.keep`), not `proguard-rules.pro`. `JniContractTest` (JVM) parses `uxplay_jni.c` and fails if a JNI name/signature drifts from the Kotlin interface.
- **ABI splits**: arm64-v8a, armeabi-v7a, x86_64 plus a universal APK; versionCode = base × 10 + ABI digit (set in `androidComponents.onVariants`).
- Type-safe project accessors (`projects.core.protocol`) are enabled; `:protocol:*` projects map to `protocol-impl/*` in `settings.gradle.kts` (root `protocol/` is not a Gradle project).
- Dependencies/versions only via `gradle/libs.versions.toml`; repositories are centralized (`FAIL_ON_PROJECT_REPOS`). Configuration cache is on — read build switches with `providers.gradleProperty`.
- Ktor CIO servers must use `LanHttpServer` (own `SupervisorJob` as parent context; otherwise the caller's `withContext` never completes).
- Unit tests avoid Robolectric/MockK (JDK 25 test JVM); use fakes. `core/protocol` tests read vectors via the `wsc.vectors.dir` system property.

## Local environment

`~/.gradle/gradle.properties` sets an HTTP(S) proxy at `127.0.0.1:7897`; when that proxy is not running, dependency resolution fails with “Could not GET …”. Pass `-Dhttp.nonProxyHosts=* -Dhttps.nonProxyHosts=*` (as in the commands above) instead of editing the global file.
