#!/bin/bash
# Собирает LD_PRELOAD-заглушку epoll_create -> epoll_create1 для nginx в Ubuntu под proot.
# Нужен только clang из NDK; без libc (-nostdlib), символ epoll_create1 берётся из glibc гостя.
set -e
cd "$(dirname "$0")"
NDK=${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* | sort -V | tail -1)}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
for p in x86_64:x86_64-linux-gnu armhf:arm-linux-gnueabihf; do
  "$CC" --target="${p#*:}" -O2 -fPIC -shared -nostdlib -fuse-ld=lld -Wl,-z,max-page-size=16384 \
    -o "../inner/epollfix-${p%%:*}.so" epollfix.c
done
ls -la ../inner/epollfix-*.so
