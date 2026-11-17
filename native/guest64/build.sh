#!/bin/sh
 
 
 
 
 
TAG=$1
[ -d "$(dirname "$0")/$TAG" ] && [ -n "$TAG" ] || { echo "usage: sh build.sh <tag>   (one of: $(cd "$(dirname "$0")" && ls -d [0-9]* | tr '\n' ' '))"; exit 1; }
cd "$(dirname "$0")/$TAG" || exit 1
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
DIR=${2:-../../../app/src/main/jniLibs/arm64-v8a}
OUT="$DIR/libaemu64_$TAG.so"
"$BIN/clang" --target=aarch64-linux-android26 -O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -mno-outline-atomics \
  -fno-pic -no-pie -Wall -Wno-unused-function -Wl,--image-base=0x2000000000 -Wl,--build-id=none -Wl,-z,norelro \
  -o "$OUT" start.S run64.c || exit 1
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
 
"$BIN/clang" --target=aarch64-linux-android26 -O2 -Wall -o "$DIR/libbinderd64_$TAG.so" binderd.c || exit 1
"$BIN/llvm-strip" "$DIR/libbinderd64_$TAG.so" 2>/dev/null || true
 
if [ -f nativewindow_stub.c ]; then
  NW="../../../app/src/main/assets/engines/n64-$TAG/system#lib64#libnativewindow.so"
  "$BIN/clang" --target=aarch64-linux-android21 -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden \
    -fno-stack-protector -Wl,--build-id=none -Wl,-soname,libnativewindow.so -o "$NW" nativewindow_stub.c -lc || exit 1
  "$BIN/llvm-strip" "$NW" 2>/dev/null || true
fi
 
if [ -f egl_wrap.c ]; then
  A="../../../app/src/main/assets/engines"
  cp "$A/n64-gl/system#lib64#egl#libEGL_swiftshader.so" "$A/n64-$TAG/system#lib64#egl#libreal_swiftshader.so" || exit 1
  "$BIN/clang" --target=aarch64-linux-android21 -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden \
    -fno-stack-protector -Wl,--build-id=none -Wl,-soname,libEGL_swiftshader.so \
    -o "$A/n64-$TAG/system#lib64#egl#libEGL_swiftshader.so" egl_wrap.c -ldl -lc || exit 1
  "$BIN/llvm-strip" "$A/n64-$TAG/system#lib64#egl#libEGL_swiftshader.so" 2>/dev/null || true
fi
 
if [ -f gles_wrap.c ]; then
  A="../../../app/src/main/assets/engines"
  cp "$A/n64-gl/system#lib64#egl#libGLESv2_swiftshader.so" "$A/n64-$TAG/system#lib64#egl#libGLESv2real_swiftshader.so" || exit 1
  "$BIN/clang" --target=aarch64-linux-android21 -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden \
    -fno-stack-protector -Wl,--build-id=none -Wl,-soname,libGLESv2_swiftshader.so \
    -o "$A/n64-$TAG/system#lib64#egl#libGLESv2_swiftshader.so" gles_wrap.c -ldl -lc || exit 1
  "$BIN/llvm-strip" "$A/n64-$TAG/system#lib64#egl#libGLESv2_swiftshader.so" 2>/dev/null || true
fi
 
 
if [ -f gralloc_exynos.c ]; then
  A="../../../app/src/main/assets/engines/n64-$TAG"
  F="-Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,gralloc.default.so"
  "$BIN/clang" --target=aarch64-linux-android21 $F -o "$A/system#lib64#libgralloc_exynos.so" gralloc_exynos.c -ldl -lc || exit 1
  "$BIN/clang" --target=armv7a-linux-androideabi21 -mthumb $F -Wl,--hash-style=both -o "$A/system#lib#libgralloc_exynos.so" gralloc_exynos.c -ldl -lc || exit 1
  "$BIN/llvm-strip" "$A/system#lib64#libgralloc_exynos.so" "$A/system#lib#libgralloc_exynos.so" 2>/dev/null || true
fi
 
 
if [ -f gralloc_qcom.c ]; then
  A="../../../app/src/main/assets/engines/n64-$TAG"
  F="-Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,gralloc.default.so"
   
  P=system; grep -q '"/vendor/lib64/libgralloc_rom.so"' gralloc_qcom.c && P=vendor
  "$BIN/clang" --target=aarch64-linux-android21 $F -o "$A/$P#lib64#libgralloc_qcom.so" gralloc_qcom.c -ldl -lc || exit 1
  "$BIN/clang" --target=armv7a-linux-androideabi21 -mthumb $F -Wl,--hash-style=both -o "$A/$P#lib#libgralloc_qcom.so" gralloc_qcom.c -ldl -lc || exit 1
  "$BIN/llvm-strip" "$A/$P#lib64#libgralloc_qcom.so" "$A/$P#lib#libgralloc_qcom.so" 2>/dev/null || true
fi
