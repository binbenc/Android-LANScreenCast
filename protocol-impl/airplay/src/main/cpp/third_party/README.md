# 兼容 Apple 设备模块的第三方源码（不随仓库提交，需手动拉取）

默认构建不需要这些源码。只有 `./gradlew assembleDebug -Pwsc.airplay=true` 时 CMake 才会用到。

| 目录 | 来源 | 许可证 | 说明 |
|---|---|---|---|
| `UxPlay/` | https://github.com/FDH2/UxPlay （开发时核对的 commit：`05b0275cb1b726592700e47a6e0307544b3d3a52`，2026-11-06） | GPL-3.0 | 只编译 `lib/` |
| `libplist/` | https://github.com/libimobiledevice/libplist （2.6.0） | LGPL-2.1 | 静态库 |
| `openssl-cmake/` | https://github.com/viaduck/openssl-cmake | MIT（构建脚本）/ OpenSSL 3.x Apache-2.0 | 需改为预置 OpenSSL 源码包，避免 CMake 配置期联网下载（F-Droid 离线构建要求，技术方案 3.10.5） |
| `ffmpeg/`（未接入） | https://ffmpeg.org （固定 tag） | LGPL-2.1+ | 只开 ALAC 解码器；音频输出 TODO(F15-audio) |

建议以 git submodule 固定 commit：

```bash
git submodule add https://github.com/FDH2/UxPlay.git protocol-impl/airplay/src/main/cpp/third_party/UxPlay
git submodule add https://github.com/libimobiledevice/libplist.git protocol-impl/airplay/src/main/cpp/third_party/libplist
git submodule add https://github.com/viaduck/openssl-cmake.git protocol-impl/airplay/src/main/cpp/third_party/openssl-cmake
```

剩余工作见 `docs/dev/开发进度.md` 的 F15 条目。
