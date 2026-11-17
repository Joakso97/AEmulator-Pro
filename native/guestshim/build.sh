#!/bin/sh
set -eu
 
NDK=${NDK:-$ANDROID_NDK_HOME}
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang"
[ -x "$CC" ] || CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
OUT=${1:-../../app/src/main/assets/engines/common/libaemushim.so}
mkdir -p "$(dirname "$OUT")"
"$CC" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -O2 -fPIC -shared -nostdlib \
  -fvisibility=hidden -fno-stack-protector -ffreestanding -fno-builtin -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-undefined \
  -Wl,-soname,libaemushim.so -Wl,--build-id=none -I../bcmgpu -o "$OUT" aemushim.c crash.c -lc
BIN="$(dirname "$CC")"
"$BIN/llvm-strip" "$OUT"
 
"$CC" --target=armv7a-linux-androideabi21 -nostdlib -static -Wl,--build-id=none \
  -o "$(dirname "$OUT")/aemu_true.so" true.S
"$BIN/llvm-strip" "$(dirname "$OUT")/aemu_true.so"
 
"$CC" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fPIC -shared -nostdlib \
  -fvisibility=hidden -fno-stack-protector -ffreestanding -fno-builtin -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-undefined \
  -Wl,-soname,librecshim.so -Wl,--build-id=none -o "$(dirname "$OUT")/librecshim.so" recshim.c -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/librecshim.so"
