# 兼容 Apple 设备模块的第三方源码（git submodule）

默认构建不需要这些源码；只有 `./gradlew assembleDebug -Pwsc.airplay=true` 才会用到。全部以 **git submodule 固定到具体 commit**（见仓库根 `.gitmodules`，均为 `shallow = true`）。

## 拉取

```bash
# 仓库根目录执行（只拉固定的那个 commit，工作区约 250 MB + .git/modules 约 120 MB，主要是 FFmpeg / OpenSSL）
git submodule update --init --depth 1 protocol-impl/airplay/src/main/cpp/third_party
```

GitHub 支持按 SHA 浅拉取，因此固定的 commit 不在默认分支末端也能 `--depth 1` 获取。

## 版本与许可证

| 目录 | 上游 | 固定版本 | commit | 许可证 | 用法 |
|---|---|---|---|---|---|
| `UxPlay/` | https://github.com/FDH2/UxPlay | master（1.73.7 之后、1.74 开发线，2026-11-06） | `05b0275cb1b726592700e47a6e0307544b3d3a52` | GPL-3.0（`lib/` 中源自 RPiPlay 的文件为 LGPL-2.1+，`lib/playfair` GPL-3.0，`lib/llhttp` MIT） | 只编译 `lib/`；不编 GStreamer 渲染、Avahi/Bonjour、`lib/mdnsd`、`lib/dns_sd` |
| `libplist/` | https://github.com/libimobiledevice/libplist | `2.8.1` | `678b1a34693b86ae1787fa470dcb7203ffd0ef3f` | LGPL-2.1+（库）；`tools/` 为 GPL-2.0+，未编译 | CMake 直接静态编译 C 库 |
| `openssl/` | https://github.com/openssl/openssl | `openssl-3.5.9`（3.5 LTS） | `45e844fa2a14ec92d146bd8f5778ac130b6625fb` | Apache-2.0 | `scripts/build-native-deps.sh` 交叉编译，只用 libcrypto，关闭大量未用算法 |
| `ffmpeg/` | https://github.com/FFmpeg/FFmpeg | `n7.1.5` | `3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587` | LGPL-2.1+（未开 `--enable-gpl` / `--enable-nonfree`） | 同上脚本交叉编译，`--disable-everything --enable-decoder=alac`，只产出 libavcodec + libavutil |

许可证兼容性：Apache-2.0、LGPL-2.1+、MIT 都可以并入 GPL-3.0 作品；UxPlay 文档指出 GPLv3 与 OpenSSL < 3.0 的许可证冲突，3.0+（Apache-2.0）不存在该问题。本应用整体以 GPL-3.0 发布，发布二进制时需同时提供上述源码（submodule 即可满足）。

**为什么 UxPlay 不用 v1.73.7 tag**：`bridge/android_dnssd.c` 实现的是 UxPlay 新的可插拔 dnssd 后端接口（`dnssd_private_init` 等，`lib/dnssd.c` 为通用前端），该接口在 v1.73.7 之后才出现；v1.73.7 的 `dnssd.c` 直接依赖 Avahi/Bonjour 的 `dns_sd.h`。升级 UxPlay 时运行 `:protocol:airplay:testDebugUnitTest`（JNI 契约 / reset_type 顺序测试）并重新做真机验证。

不再使用 `viaduck/openssl-cmake`：它在 CMake 配置期联网下载 OpenSSL 源码，F-Droid 离线构建会失败；现在 OpenSSL 本身就是 submodule。也不使用 Oboe：音频输出走 `AudioTrack`（Kotlin），原生代码全部是 C，`ANDROID_STL=none`。

## 构建产物（不入库）

`scripts/build-native-deps.sh` 输出到 `protocol-impl/airplay/native-deps/<abi>/{include,lib}`（已在 `.gitignore`；另外全局忽略 `*.so` / `*.a`）。Gradle 任务 `:protocol:airplay:buildNativeDeps` 在 CMake 配置前自动调用它；脚本按“子模块 commit + 脚本哈希 + NDK 版本”写 stamp，未变化时秒级跳过。首次三个 ABI 全量约 2.5 分钟（10 核）。

```bash
# 手动执行（与 Gradle 调用等价）
protocol-impl/airplay/scripts/build-native-deps.sh --ndk ~/Library/Android/sdk/ndk/27.0.12077973
# 修改 OpenSSL 选项后：用同一组选项为本机编译并跑算法自检（Ed25519 PEM、X25519、AES-GCM、SHA/MD5、BN）
protocol-impl/airplay/scripts/build-native-deps.sh --host-selftest
```

## 构建期补丁

不修改子模块源码。`CMakeLists.txt` 在配置期把 `UxPlay/lib/airplay_video.c` 复制到构建目录并替换一处 `exit(1)`（未知的 HLS `EXT-X-MEDIA TYPE` 会让整个进程退出，属于远端可触发；改为忽略该行）。上游改动导致补丁不匹配时 CMake 给出 WARNING 并使用原文件。
