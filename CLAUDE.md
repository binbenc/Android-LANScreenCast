# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

WirelessScreenCasting is an Android app (package `com.benchan.wireless.screencasting`) that is still at the Android Studio "Empty Views Activity" template stage: a single `:app` module with one `MainActivity` (View-based, `AppCompatActivity` + XML layout, edge-to-edge insets handling) and the default example tests. No screen-casting logic exists yet; update this file as the architecture takes shape.

The product spec lives in `docs/prd/PRD-局域网投屏-v1.0.md` (Chinese; pain-point research in `docs/prd/research/`). Read it before designing features. Decisions already locked there (section 16) that affect code:

- Single APK with sender/receiver dual mode; UI to be migrated to Compose (Compose for TV on the receiver).
- Transport is WebRTC (host ICE candidates only, no STUN/TURN — the app must make zero internet connections); a custom "WSC" signaling protocol runs over WebSocket+TLS, discovered via mDNS `_wsc._tcp`.
- Receiver also implements DLNA DMR and AirPlay (UxPlay via JNI, built in but off by default, behind a build switch).
- minSdk is planned to move from 24 to 29; project license GPL-3.0; distribution GitHub + F-Droid (no Google Play Services dependencies).
- No accounts, ads, analytics or crash-reporting SDKs.
- Planned module split is in PRD section 11.1; the technical design (libwebrtc fork, audio-capture patch, SPAKE2 pairing, self-built DLNA, UxPlay build) is in `docs/tech/技术方案-v1.0.md`, and the UI spec/color tokens are in `docs/design/交互稿与色卡-v1.0.html`.

## Commands

```bash
./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # build + install on connected device/emulator
./gradlew assembleRelease               # release build (R8 optimization enabled)
./gradlew lint                          # Android lint
./gradlew test                          # JVM unit tests (app/src/test)
./gradlew connectedAndroidTest          # instrumented tests (app/src/androidTest), needs a device

# Single test class / method
./gradlew :app:testDebugUnitTest --tests "com.benchan.wireless.screencasting.ExampleUnitTest"
./gradlew :app:testDebugUnitTest --tests "com.benchan.wireless.screencasting.ExampleUnitTest.addition_isCorrect"
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.benchan.wireless.screencasting.ExampleInstrumentedTest
```

## Build setup (non-obvious bits)

- **Bleeding-edge toolchain**: AGP 9.4.1, Gradle 9.6.0, Gradle daemon JVM toolchain 25 (auto-provisioned via foojay, see `gradle/gradle-daemon-jvm.properties`). compileSdk/targetSdk 37, minSdk 24, Java 11 bytecode.
- **AGP 9 built-in Kotlin**: no `org.jetbrains.kotlin.android` plugin is applied — Kotlin compilation is provided by AGP itself. Don't add the standalone Kotlin Android plugin; if Compose is added, only the Compose compiler plugin is needed.
- **New AGP DSL**: `compileSdk { version = release(37) }` and the release `optimization { enable = true; packageScope = ... }` block replace the old `minifyEnabled`/`proguardFiles`. R8 keep rules live in `app/src/main/keepRules/*.keep` (AGP merges all files there), not `proguard-rules.pro`.
- Dependencies and plugin versions are managed in the version catalog `gradle/libs.versions.toml`; reference them as `libs.*`. Repositories are centralized in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`), so don't declare repositories in module build files.
- Configuration cache is enabled (`gradle.properties`); custom build logic must be configuration-cache compatible.
