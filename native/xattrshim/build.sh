#!/bin/sh
set -eu
 
NDK=${NDK:-$ANDROID_NDK_HOME}
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang"
[ -x "$CC" ] || CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
OUT=${1:-../../app/src/main/assets/engines/common/libaemuxattr.so}
mkdir -p "$(dirname "$OUT")"
"$CC" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fomit-frame-pointer -fPIC -shared -nostdlib \
  -fvisibility=hidden -fno-stack-protector -ffreestanding -fno-builtin -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-undefined \
  -Wl,-soname,libaemuxattr.so -Wl,--build-id=none -o "$OUT" xattr_shim.c -lc
"$(dirname "$CC")/llvm-strip" "$OUT"
