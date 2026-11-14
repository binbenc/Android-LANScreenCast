#!/usr/bin/env bash
# WirelessScreenCasting — 兼容 Apple 设备模块的原生依赖交叉编译（GPL-3.0，技术方案 3.8）。
#
# 为每个 ABI 从 third_party 子模块源码静态编译：
#   - OpenSSL 3.x（Apache-2.0）：只用 libcrypto（UxPlay 用到 EVP / BN / PEM / RAND / SHA / AES / Ed25519 / X25519），
#     关闭 UxPlay 用不到的算法与协议以减小体积
#   - FFmpeg（LGPL-2.1+，未开 --enable-gpl）：只开 ALAC 解码器（libavcodec + libavutil）
# libplist 与 UxPlay lib/ 源码量小，直接由 CMakeLists.txt 编译，不在这里处理。
#
# 输出：<out>/<abi>/{include,lib}，每个 ABI 一个 .stamp（记录子模块 commit + 本脚本哈希 + NDK 版本），
# 未变化时直接跳过，因此可以反复执行（Gradle 的 buildNativeDeps 任务每次都会调用它）。
# 源码树保持干净：OpenSSL / FFmpeg 都在 <out>/.build/ 下做 out-of-tree 构建。
#
# 用法：
#   scripts/build-native-deps.sh --ndk <ndk 目录> [--abis "arm64-v8a armeabi-v7a x86_64"] [--api 29] [--out <目录>] [--jobs N]
#   scripts/build-native-deps.sh --host-selftest     # 用同一组 OpenSSL 选项为本机编译并运行 openssl-selftest.c，
#                                                     # 确认裁剪后 UxPlay 需要的算法仍可用（无需 NDK / 设备）
# 也可用环境变量 ANDROID_NDK_ROOT 代替 --ndk。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TP="$MODULE_DIR/src/main/cpp/third_party"

NDK="${ANDROID_NDK_ROOT:-}"
ABIS="arm64-v8a armeabi-v7a x86_64"
API=29
OUT="$MODULE_DIR/native-deps"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
HOST_SELFTEST=0

# OpenSSL 选项（Android 与 --host-selftest 共用）：只要 libcrypto，关闭 UxPlay 用不到的算法 / 协议 / 组件。
# UxPlay lib 用到：AES-128-GCM/CTR/CBC、SHA-1/224/256/384/512、MD5、Ed25519、X25519、BN、PEM（PKCS#8）、RAND。
# 改动后务必运行 --host-selftest：算法缺失只会在运行时 EVP fetch 失败，链接期发现不了。
OPENSSL_OPTIONS=(
    no-shared no-module no-dso no-engine no-apps no-tests no-docs no-ui-console
    no-comp no-ssl3 no-dtls no-srp no-psk no-legacy no-sock
    no-des no-rc2 no-rc4 no-idea no-bf no-cast no-camellia no-aria no-seed no-sm2 no-sm3 no-sm4
    no-whirlpool no-mdc2 no-md4 no-rmd160 no-blake2 no-siphash no-dsa no-ec2m no-gost
    no-cms no-ts no-ocsp no-ct no-cmp no-srtp no-scrypt no-quic
    no-argon2 no-chacha no-poly1305 no-ml-kem no-ml-dsa no-slh-dsa no-siv no-ocb no-dh no-rfc3779 no-sm2-precomp
)

while [[ $# -gt 0 ]]; do
    case "$1" in
        --ndk) NDK="$2"; shift 2 ;;
        --abis) ABIS="$2"; shift 2 ;;
        --api) API="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --jobs) JOBS="$2"; shift 2 ;;
        --host-selftest) HOST_SELFTEST=1; shift ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

die() { echo "error: $*" >&2; exit 1; }
log() { echo "[native-deps] $*"; }

# —— 本机自检：同一组 OpenSSL 选项编译到 <out>/.host，运行 scripts/openssl-selftest.c ——
if [[ "$HOST_SELFTEST" == 1 ]]; then
    [[ -f "$TP/openssl/Configure" ]] || die "缺少 OpenSSL 子模块源码（git submodule update --init --depth 1 protocol-impl/airplay/src/main/cpp/third_party）"
    case "$(uname -s)-$(uname -m)" in
        Darwin-arm64) host_target=darwin64-arm64-cc ;;
        Darwin-x86_64) host_target=darwin64-x86_64-cc ;;
        Linux-x86_64) host_target=linux-x86_64 ;;
        Linux-aarch64) host_target=linux-aarch64 ;;
        *) die "unsupported host $(uname -s)-$(uname -m)" ;;
    esac
    hdir="$OUT/.host"
    rm -rf "$hdir" && mkdir -p "$hdir/build"
    log "host: OpenSSL configure ($host_target)"
    (
        cd "$hdir/build"
        perl "$TP/openssl/Configure" "$host_target" --prefix="$hdir" --openssldir=/nonexistent --libdir=lib \
            "${OPENSSL_OPTIONS[@]}" >configure.log 2>&1 || { tail -30 configure.log; exit 1; }
        log "host: OpenSSL build (make -j$JOBS)"
        make -j"$JOBS" build_libs >build.log 2>&1 || { tail -40 build.log; exit 1; }
        make install_dev >install.log 2>&1 || { tail -20 install.log; exit 1; }
    )
    cc -std=c17 -Wall -I"$hdir/include" "$SCRIPT_DIR/openssl-selftest.c" "$hdir/lib/libcrypto.a" -lpthread -o "$hdir/openssl-selftest"
    "$hdir/openssl-selftest"
    rm -rf "$hdir"
    exit 0
fi

# —— 前置检查：缺什么就明确说缺什么，不生成任何替代实现 ——
[[ -n "$NDK" && -d "$NDK" ]] || die "未找到 Android NDK（--ndk 或 ANDROID_NDK_ROOT）。请在 SDK Manager 安装 NDK 27.0.12077973。"
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) die "不支持的构建主机：$(uname -s)（需要 macOS 或 Linux）" ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
[[ -x "$TC/bin/clang" ]] || die "NDK 工具链不完整：$TC/bin/clang 不存在"
command -v perl >/dev/null || die "需要 perl（OpenSSL Configure）"
command -v make >/dev/null || die "需要 make"

missing=()
[[ -f "$TP/openssl/Configure" ]] || missing+=("openssl")
[[ -f "$TP/ffmpeg/configure" ]] || missing+=("ffmpeg")
[[ -f "$TP/libplist/include/plist/plist.h" ]] || missing+=("libplist")
[[ -f "$TP/UxPlay/lib/raop.h" ]] || missing+=("UxPlay")
if [[ ${#missing[@]} -gt 0 ]]; then
    die "缺少第三方子模块源码：${missing[*]}
请在仓库根目录执行：
  git submodule update --init --depth 1 protocol-impl/airplay/src/main/cpp/third_party
（详见 protocol-impl/airplay/src/main/cpp/third_party/README.md）"
fi

NDK_REV="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties" 2>/dev/null || echo unknown)"
script_hash() { shasum -a 256 "$0" | cut -c1-16; }
commit_of() { git -C "$1" rev-parse HEAD 2>/dev/null || echo "nogit"; }
STAMP_CONTENT="script=$(script_hash) ndk=$NDK_REV api=$API openssl=$(commit_of "$TP/openssl") ffmpeg=$(commit_of "$TP/ffmpeg")"

export PATH="$TC/bin:$PATH"
export ANDROID_NDK_ROOT="$NDK"

build_openssl() { # abi prefix builddir
    local abi="$1" prefix="$2" bdir="$3" target
    case "$abi" in
        arm64-v8a) target=android-arm64 ;;
        armeabi-v7a) target=android-arm ;;
        x86_64) target=android-x86_64 ;;
        *) die "unsupported ABI $abi" ;;
    esac
    rm -rf "$bdir" && mkdir -p "$bdir"
    log "$abi: OpenSSL configure ($target, API $API)"
    (
        cd "$bdir"
        perl "$TP/openssl/Configure" "$target" "-D__ANDROID_API__=$API" \
            --prefix="$prefix" --openssldir=/nonexistent --libdir=lib \
            "${OPENSSL_OPTIONS[@]}" \
            -fPIC -ffile-prefix-map="$TP"=. -O2 >configure.log 2>&1 || { tail -30 configure.log; exit 1; }
        log "$abi: OpenSSL build (make -j$JOBS)"
        make -j"$JOBS" build_libs >build.log 2>&1 || { tail -40 build.log; exit 1; }
        make install_dev >install.log 2>&1 || { tail -20 install.log; exit 1; }
    )
}

build_ffmpeg() { # abi prefix builddir
    local abi="$1" prefix="$2" bdir="$3" arch cpu triple extra=()
    case "$abi" in
        arm64-v8a) arch=aarch64; cpu=armv8-a; triple=aarch64-linux-android ;;
        armeabi-v7a) arch=arm; cpu=armv7-a; triple=armv7a-linux-androideabi; extra=(--enable-neon --enable-thumb) ;;
        x86_64) arch=x86_64; cpu=x86-64; triple=x86_64-linux-android; extra=(--disable-x86asm) ;; # 不依赖 nasm
        *) die "unsupported ABI $abi" ;;
    esac
    rm -rf "$bdir" && mkdir -p "$bdir"
    log "$abi: FFmpeg configure (ALAC decoder only)"
    (
        cd "$bdir"
        "$TP/ffmpeg/configure" \
            --prefix="$prefix" --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
            --cc="$TC/bin/${triple}${API}-clang" --cxx="$TC/bin/${triple}${API}-clang++" \
            --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
            --sysroot="$TC/sysroot" --pkg-config=false \
            --enable-static --disable-shared --enable-pic --disable-debug --disable-doc --disable-programs \
            --disable-autodetect --disable-everything --disable-network \
            --disable-avdevice --disable-avformat --disable-avfilter --disable-swscale --disable-swresample --disable-postproc \
            --enable-avcodec --enable-avutil --enable-decoder=alac \
            --extra-cflags="-O2 -ffile-prefix-map=$TP=." \
            ${extra[@]+"${extra[@]}"} >configure.log 2>&1 || { tail -30 configure.log; tail -30 ffbuild/config.log 2>/dev/null; exit 1; }
        log "$abi: FFmpeg build (make -j$JOBS)"
        make -j"$JOBS" >build.log 2>&1 || { tail -40 build.log; exit 1; }
        make install >install.log 2>&1 || { tail -20 install.log; exit 1; }
    )
}

for abi in $ABIS; do
    prefix="$OUT/$abi"
    stamp="$prefix/.stamp"
    if [[ -f "$stamp" && "$(cat "$stamp")" == "$STAMP_CONTENT" \
          && -f "$prefix/lib/libcrypto.a" && -f "$prefix/lib/libavcodec.a" && -f "$prefix/lib/libavutil.a" ]]; then
        log "$abi: up to date"
        continue
    fi
    rm -rf "$prefix"
    mkdir -p "$prefix"
    build_openssl "$abi" "$prefix" "$OUT/.build/$abi/openssl"
    build_ffmpeg "$abi" "$prefix" "$OUT/.build/$abi/ffmpeg"
    # 只保留需要的产物，减少磁盘占用
    rm -f "$prefix/lib/libssl.a"
    rm -rf "$prefix/lib/pkgconfig" "$prefix/share" "$OUT/.build/$abi"
    echo "$STAMP_CONTENT" >"$stamp"
    log "$abi: done → $prefix"
done
