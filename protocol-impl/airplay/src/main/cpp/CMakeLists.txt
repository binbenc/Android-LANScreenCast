# WirelessScreenCasting — 兼容 Apple 设备（非官方）原生构建（GPL-3.0，技术方案 3.8）。
# 只有 ./gradlew ... -Pwsc.airplay=true 时才会执行本文件；默认构建不包含。
#
# 依赖：
#   third_party/UxPlay   （GPL-3.0，git submodule）     只编译 lib/（协议部分），不用 GStreamer / Avahi / 内置 mdnsd
#   third_party/libplist （LGPL-2.1+，git submodule）   在这里直接静态编译
#   OpenSSL 3.x libcrypto（Apache-2.0）、FFmpeg ALAC 解码器（LGPL-2.1+）：
#       由 scripts/build-native-deps.sh 预先交叉编译到 WSC_NATIVE_DEPS_DIR/<abi>/{include,lib}
#       （Gradle 任务 :protocol:airplay:buildNativeDeps 会在 CMake 配置前自动执行）
cmake_minimum_required(VERSION 3.22)
project(wscairplay C)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(UXPLAY_LIB ${TP}/UxPlay/lib)
set(PLIST_DIR ${TP}/libplist)
if(NOT WSC_NATIVE_DEPS_DIR)
    set(WSC_NATIVE_DEPS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../native-deps)
endif()
set(DEPS ${WSC_NATIVE_DEPS_DIR}/${ANDROID_ABI})

# —— 依赖检查：缺少源码或预编译依赖时直接失败，不生成假实现 ——
foreach(req ${UXPLAY_LIB}/raop.h ${PLIST_DIR}/include/plist/plist.h)
    if(NOT EXISTS ${req})
        message(FATAL_ERROR
            "兼容 Apple 设备模块缺少第三方源码：${req}\n"
            "请在仓库根目录执行 git submodule update --init --depth 1 protocol-impl/airplay/src/main/cpp/third_party\n"
            "（见 protocol-impl/airplay/src/main/cpp/third_party/README.md），或去掉 -Pwsc.airplay=true 构建默认版本。")
    endif()
endforeach()
foreach(req ${DEPS}/lib/libcrypto.a ${DEPS}/lib/libavcodec.a ${DEPS}/lib/libavutil.a ${DEPS}/include/openssl/evp.h)
    if(NOT EXISTS ${req})
        message(FATAL_ERROR
            "缺少 ${ANDROID_ABI} 的预编译原生依赖：${req}\n"
            "请执行 protocol-impl/airplay/scripts/build-native-deps.sh --ndk <NDK 目录>（Gradle 任务 :protocol:airplay:buildNativeDeps 会自动调用）。")
    endif()
endforeach()

set(CMAKE_C_STANDARD 17)
# 可复现构建：去掉绝对路径；默认隐藏符号，只导出 JNI_OnLoad
add_compile_options(-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=. -fvisibility=hidden -ffunction-sections -fdata-sections)

# —— 预编译静态库 ——
add_library(wsc_crypto STATIC IMPORTED)
set_target_properties(wsc_crypto PROPERTIES IMPORTED_LOCATION ${DEPS}/lib/libcrypto.a INTERFACE_INCLUDE_DIRECTORIES ${DEPS}/include)
add_library(wsc_avutil STATIC IMPORTED)
set_target_properties(wsc_avutil PROPERTIES IMPORTED_LOCATION ${DEPS}/lib/libavutil.a INTERFACE_INCLUDE_DIRECTORIES ${DEPS}/include)
add_library(wsc_avcodec STATIC IMPORTED)
set_target_properties(wsc_avcodec PROPERTIES IMPORTED_LOCATION ${DEPS}/lib/libavcodec.a INTERFACE_INCLUDE_DIRECTORIES ${DEPS}/include
    INTERFACE_LINK_LIBRARIES "wsc_avutil;m")

# —— libplist（LGPL-2.1+）：只编 C 库，不编 C++ 封装 ——
file(STRINGS ${PLIST_DIR}/NEWS PLIST_NEWS_FIRST LIMIT_COUNT 1)
string(REGEX MATCH "[0-9]+\\.[0-9]+\\.[0-9]+" PLIST_VERSION "${PLIST_NEWS_FIRST}")
if(NOT PLIST_VERSION)
    set(PLIST_VERSION "2.8.1")
endif()
add_library(plist STATIC
    ${PLIST_DIR}/src/base64.c ${PLIST_DIR}/src/bytearray.c ${PLIST_DIR}/src/hashtable.c ${PLIST_DIR}/src/ptrarray.c
    ${PLIST_DIR}/src/time64.c ${PLIST_DIR}/src/common.c ${PLIST_DIR}/src/xplist.c ${PLIST_DIR}/src/bplist.c
    ${PLIST_DIR}/src/jsmn.c ${PLIST_DIR}/src/jplist.c ${PLIST_DIR}/src/oplist.c ${PLIST_DIR}/src/out-default.c
    ${PLIST_DIR}/src/out-plutil.c ${PLIST_DIR}/src/out-limd.c ${PLIST_DIR}/src/plist.c
    ${PLIST_DIR}/libcnary/node.c ${PLIST_DIR}/libcnary/node_list.c)
target_include_directories(plist PUBLIC ${PLIST_DIR}/include PRIVATE ${PLIST_DIR}/src ${PLIST_DIR}/libcnary/include)
target_compile_definitions(plist
    PUBLIC LIBPLIST_STATIC
    PRIVATE PACKAGE_VERSION="${PLIST_VERSION}" HAVE_STRNDUP HAVE_MEMMEM HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE
            HAVE_TIMEGM HAVE_LOCALTIME_R HAVE_GMTIME_R)
target_compile_options(plist PRIVATE -Wno-unused-parameter -Wno-sign-compare)

# —— UxPlay lib/（GPL-3.0）：协议 + 内嵌 llhttp（MIT）/ playfair ——
# dnssd.c 是通用前端；后端（dnssd_private_* 等）由 bridge/android_dnssd.c 实现并转到 NsdManager，
# 因此不编译 lib/mdnsd/ 与 lib/dns_sd/。
file(GLOB UX_SRC ${UXPLAY_LIB}/*.c ${UXPLAY_LIB}/llhttp/*.c ${UXPLAY_LIB}/playfair/*.c)

# 构建期补丁（不改子模块）：airplay_video.c 遇到未知的 HLS EXT-X-MEDIA TYPE 会 exit(1)，
# 这是远端可触发的进程退出；改为忽略该行。上游改动导致匹配失败时给出警告并使用原文件。
file(READ ${UXPLAY_LIB}/airplay_video.c AV_SRC)
set(AV_EXIT "printf(\"invalid EXT-X-MEDIA tag  TYPE=%s\",type);\n                exit(1);")
string(FIND "${AV_SRC}" "${AV_EXIT}" AV_POS)
if(AV_POS GREATER -1)
    string(REPLACE "${AV_EXIT}" "slice[index].type = '\\0'; /* WSC patch: ignore unknown TYPE instead of exit(1) */" AV_SRC "${AV_SRC}")
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/uxplay_patched/airplay_video.c "${AV_SRC}")
    list(REMOVE_ITEM UX_SRC ${UXPLAY_LIB}/airplay_video.c)
    list(APPEND UX_SRC ${CMAKE_CURRENT_BINARY_DIR}/uxplay_patched/airplay_video.c)
else()
    message(WARNING "UxPlay airplay_video.c exit(1) patch did not apply; review protocol-impl/airplay/src/main/cpp/CMakeLists.txt")
endif()

# 构建期补丁（不改子模块）：PIN 配对改由 bridge/wsc_pin_guard.c 管理（原因见 wsc_pin_guard.h）。
# 上游 PIN 只能用一次、用后清零为 "0000"：macOS 输错后重试必然失败，且不经 PAIR-PIN-START 也能拿 "0000" 尝试。
# raop_handlers.h 只被 raop.c 以 #include "..." 引入，所以把两者一起写到 uxplay_patched/（同目录优先）。
# 这是安全相关补丁，任何一处匹配失败都直接终止构建。
file(READ ${UXPLAY_LIB}/raop_handlers.h RH_SRC)
file(READ ${UXPLAY_LIB}/raop.c RAOP_SRC)
# 分号会被 CMake 当作列表分隔符，所以每段补丁用单独的变量，逐个应用
macro(wsc_patch_rh FROM TO)
    string(FIND "${RH_SRC}" "${${FROM}}" RH_POS)
    if(RH_POS EQUAL -1)
        message(FATAL_ERROR "UxPlay raop_handlers.h PIN patch ${FROM} did not apply; review protocol-impl/airplay/src/main/cpp/CMakeLists.txt")
    endif()
    string(REPLACE "${${FROM}}" "${${TO}}" RH_SRC "${RH_SRC}")
endmacro()
# 1. PAIR-PIN-START 签发随机 PIN
set(RH_P1_FROM [=[            raop->pin = (unsigned short) pin_4 % 10000;
]=])
set(RH_P1_TO [=[            raop->pin = (unsigned short) pin_4 % 10000;
            wsc_pin_issued(); /* WSC patch */
]=])
wsc_patch_rh(RH_P1_FROM RH_P1_TO)
# 2. pair-setup-pin 第一步：PIN 未签发 / 已过期 / 失败次数用尽 → 直接拒绝；不再用后清零
set(RH_P2_FROM [=[        snprintf(pin, 6, "%04u", raop->pin % 10000);
        if (raop->pin < 10000) {
            raop->pin = 0;
        }
]=])
set(RH_P2_TO [=[        if (raop->pin < 10000 && !wsc_pin_usable()) { /* WSC patch */
            logger_log(raop->logger, LOGGER_ERR, "pair-setup-pin rejected: no valid PIN issued (start pairing again)");
            plist_mem_free(user);
            plist_free(req_root_node);
            goto authentication_failed;
        }
        snprintf(pin, 6, "%04u", raop->pin % 10000);
]=])
wsc_patch_rh(RH_P2_FROM RH_P2_TO)
# 3. 校验失败计数；用尽后作废并收起屏幕上的 PIN（display_pin 传空串）
set(RH_P3_FROM [=[            logger_log(raop->logger, LOGGER_ERR, "Client Authentication Failure (client proof not validated)");
]=])
set(RH_P3_TO [=[            logger_log(raop->logger, LOGGER_ERR, "Client Authentication Failure (client proof not validated)");
            if (raop->pin < 10000 && wsc_pin_failed() && raop->callbacks.display_pin) { /* WSC patch */
                raop->callbacks.display_pin(raop->callbacks.cls, "");
            }
]=])
wsc_patch_rh(RH_P3_FROM RH_P3_TO)
# 4. 配对成功：作废 PIN 并收起屏幕上的 PIN
set(RH_P4_FROM [=[            logger_log(raop->logger, LOGGER_DEBUG, "pair-pin-setup success\n");
]=])
set(RH_P4_TO [=[            logger_log(raop->logger, LOGGER_INFO, "pair-pin-setup success");
            if (raop->pin < 10000) { /* WSC patch */
                wsc_pin_reset();
                if (raop->callbacks.display_pin) raop->callbacks.display_pin(raop->callbacks.cls, "");
            }
]=])
wsc_patch_rh(RH_P4_FROM RH_P4_TO)
string(FIND "${RAOP_SRC}" "#include \"raop_handlers.h\"" RAOP_POS)
if(RAOP_POS EQUAL -1)
    message(FATAL_ERROR "UxPlay raop.c include anchor not found; review protocol-impl/airplay/src/main/cpp/CMakeLists.txt")
endif()
string(REPLACE "#include \"raop_handlers.h\"" "#include \"wsc_pin_guard.h\"\n#include \"raop_handlers.h\"" RAOP_SRC "${RAOP_SRC}")
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/uxplay_patched/raop_handlers.h "${RH_SRC}")
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/uxplay_patched/raop.c "${RAOP_SRC}")
list(REMOVE_ITEM UX_SRC ${UXPLAY_LIB}/raop.c)
list(APPEND UX_SRC ${CMAKE_CURRENT_BINARY_DIR}/uxplay_patched/raop.c)

add_library(airplay STATIC ${UX_SRC} bridge/android_dnssd.c bridge/wsc_pin_guard.c)
target_include_directories(airplay PUBLIC ${UXPLAY_LIB} ${UXPLAY_LIB}/llhttp ${UXPLAY_LIB}/playfair bridge)
# 与上游 lib/CMakeLists.txt 的 Linux 配置一致；libplist ≥2.3 → PLIST_210 / PLIST_230
target_compile_definitions(airplay PUBLIC
    NOHOLD OPENSSL_API_COMPAT=0x10101000L PLIST_210 PLIST_230 _REENTRANT _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64)
target_compile_options(airplay PRIVATE -Wno-unused-parameter -Wno-sign-compare -Wno-unused-variable -Wno-unused-function
    -Wno-deprecated-declarations -Wno-pointer-sign -Wno-int-conversion -Wno-incompatible-pointer-types-discards-qualifiers)
target_link_libraries(airplay PUBLIC plist wsc_crypto)

# —— JNI 桥 ——
add_library(wscairplay SHARED bridge/uxplay_jni.c bridge/alac_decoder.c)
target_compile_options(wscairplay PRIVATE -Wall -Wextra -Werror=implicit-function-declaration)
target_link_libraries(wscairplay PRIVATE airplay wsc_avcodec log android)
target_link_options(wscairplay PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1)
