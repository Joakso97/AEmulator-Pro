#!/bin/sh
 
 
 
TAG=${1:?usage: sh build32.sh <kernel tag>}
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="../../app/src/main/assets/engines/n64-$TAG/arm32/vendor#lib#hw#power.default.so"
"$BIN/clang" --target=armv7a-linux-androideabi21 -mthumb -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-z,max-page-size=65536 -Wl,--hash-style=both -Wl,-soname,power.default.so \
  -o "$OUT" power_hal.c || exit 1
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
