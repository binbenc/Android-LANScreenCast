# WirelessScreenCasting — 兼容 Apple 设备（非官方）原生构建（GPL-3.0，技术方案 3.8）。
# 只有 ./gradlew ... -Pwsc.airplay=true 时才会执行本文件；默认构建不包含。
cmake_minimum_required(VERSION 3.22)
project(wscairplay C CXX)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(UXPLAY_LIB ${TP}/UxPlay/lib)

# —— 依赖检查：缺少源码时直接失败，不生成假实现 ——
foreach(req ${UXPLAY_LIB}/raop.h ${TP}/libplist/include/plist/plist.h ${TP}/openssl-cmake/CMakeLists.txt)
    if(NOT EXISTS ${req})
        message(FATAL_ERROR
            "兼容 Apple 设备模块缺少第三方源码：${req}\n"
            "请按 protocol-impl/airplay/src/main/cpp/third_party/README.md 拉取 UxPlay、libplist、openssl-cmake（OpenSSL 3.x）后重试，"
            "或去掉 -Pwsc.airplay=true 构建默认版本。")
    endif()
endforeach()

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
add_compile_options(-ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -fvisibility=hidden)

# OpenSSL 3.x（Apache-2.0；UxPlay 文档指出 GPLv3 链接 OpenSSL < 3.0 有许可证问题）
set(BUILD_OPENSSL ON CACHE BOOL "" FORCE)
set(OPENSSL_BUILD_VERSION 3.4.4 CACHE STRING "" FORCE)
add_subdirectory(${TP}/openssl-cmake openssl EXCLUDE_FROM_ALL)

# libplist（LGPL-2.1）：静态编译
file(GLOB PLIST_SRC ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC ${TP}/libplist/include ${TP}/libplist/libcnary/include PRIVATE ${TP}/libplist/src)
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H=0 PACKAGE_STRING="libplist")

# UxPlay lib/：只编译协议部分；不用 Avahi/Bonjour/内置 mdnsd，由 bridge/android_dnssd.c 接到 NsdManager
file(GLOB UX_SRC ${UXPLAY_LIB}/*.c ${UXPLAY_LIB}/llhttp/*.c ${UXPLAY_LIB}/playfair/*.c)
list(FILTER UX_SRC EXCLUDE REGEX ".*/dnssd\\.c$")
add_library(airplay STATIC ${UX_SRC} ${UXPLAY_LIB}/dnssd.c bridge/android_dnssd.c)
target_include_directories(airplay PUBLIC ${UXPLAY_LIB} ${UXPLAY_LIB}/llhttp ${UXPLAY_LIB}/playfair bridge)
target_link_libraries(airplay PUBLIC plist crypto z)

# JNI 桥
add_library(wscairplay SHARED bridge/uxplay_jni.c)
target_link_libraries(wscairplay PRIVATE airplay log android)
target_link_options(wscairplay PRIVATE -Wl,-z,max-page-size=16384)
# TODO(F15-audio): FFmpeg（--disable-everything --enable-decoder=alac）+ Oboe（prefab）音频输出
