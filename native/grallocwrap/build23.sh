#!/bin/sh
 
 
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$(dirname "$0")" || exit 1
D=../../app/src/main/assets/engines/n64-23
[ -f "$D/system#lib64#libgralloc_aosp.so" ] || { echo "missing $D/system#lib64#libgralloc_aosp.so (6.0 AOSP gralloc.default.so)"; exit 1; }
OUT="$D/system#lib64#hw#gralloc.default.so"
MSYS_NO_PATHCONV=1 "$BIN/clang" --target=aarch64-linux-android23 -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector -mno-outline-atomics \
  -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,gralloc.default.so \
  -DAEMU_META -DNOTE_FB_USAGE '-DREAL_DIR="/system"' -o "$OUT" gralloc_wrap.c -ldl -lc || exit 1
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
ls -la "$OUT"
 
[ -f "$D/system#lib#libgralloc_aosp.so" ] || { echo "missing $D/system#lib#libgralloc_aosp.so"; exit 1; }
OUT32="$D/system#lib#hw#gralloc.default.so"
MSYS_NO_PATHCONV=1 "$BIN/clang" --target=armv7a-linux-androideabi23 -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector \
  -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,gralloc.default.so \
  -DAEMU_META -DNOTE_FB_USAGE '-DREAL_DIR="/system"' -o "$OUT32" gralloc_wrap.c -ldl -lc "$(ls "$BIN"/../lib/clang/*/lib/linux/libclang_rt.builtins-arm-android.a | head -1)" || exit 1
"$BIN/llvm-strip" "$OUT32" 2>/dev/null || true
ls -la "$OUT32"
