#!/bin/sh
 
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT=${1:-../../app/src/main/assets/engines/common/camera.default.so}
mkdir -p "$(dirname "$OUT")"
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,camera.default.so \
  -o "$OUT" camera_hal.c -ldl -lc
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
 
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,libcamera.so \
  -o "$(dirname "$OUT")/libcamera.none.so" camera_gb_none.c
"$BIN/llvm-strip" "$(dirname "$OUT")/libcamera.none.so" 2>/dev/null || true
 
"$BIN/clang" --target=aarch64-linux-android26 -DCAM_MODULE_24 -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector \
  -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,camera.default.so \
  -o "$(dirname "$OUT")/../n64-${TAG:-27}/system#lib64#hw#camera.default.so" camera_hal.c -ldl -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/../n64-${TAG:-27}/system#lib64#hw#camera.default.so" 2>/dev/null || true
 
 
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -DCAM_MODULE_24 -Os -fPIC -shared   -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector   -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,camera.default.so   -o "$(dirname "$OUT")/../n64-25/system#lib#hw#camera.default.so" camera_hal.c -ldl -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/../n64-25/system#lib#hw#camera.default.so" 2>/dev/null || true
