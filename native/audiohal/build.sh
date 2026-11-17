#!/bin/sh
 
 
 
 
 
 
 
 
set -eu
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT=${1:-../../app/src/main/assets/engines/kk/audio.primary.aosp.so}
mkdir -p "$(dirname "$OUT")"
NOE="-fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables"
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -mfloat-abi=softfp -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,-soname,audio.primary.default.so \
  -o "$OUT" audio_hal.c -lc
"$BIN/llvm-strip" "$OUT"
 
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -mfloat-abi=softfp -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE -DAEMU_DIRECTTRACK \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,audio.primary.default.so \
  -o "$(dirname "$OUT")/audio.primary.directtrack.so" audio_hal.c -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/audio.primary.directtrack.so"
 
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -mfloat-abi=softfp -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE -DAEMU_ICS \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,-soname,audio.primary.default.so \
  -o "$(dirname "$OUT")/audio.primary.ics.so" audio_hal.c -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/audio.primary.ics.so" 2>/dev/null || true
 
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -mfloat-abi=softfp -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE -DAEMU_ICS -DAEMU_SLOT_SHIFT=12 \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,-soname,audio.primary.default.so \
  -o "$(dirname "$OUT")/audio.primary.ics-samsung.so" audio_hal.c -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/audio.primary.ics-samsung.so" 2>/dev/null || true
 
for v in ics; do
  [ $v = ics ] && D=-DAEMU_ICS || D=
  "$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -mfloat-abi=softfp -Os -fPIC -shared \
    -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE $D -DAEMU_QCOM \
    -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,-soname,audio.primary.default.so \
    -o "$(dirname "$OUT")/audio.primary.$v-qcom.so" audio_hal.c -lc
  "$BIN/llvm-strip" "$(dirname "$OUT")/audio.primary.$v-qcom.so" 2>/dev/null || true
done
 
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -mfloat-abi=softfp -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE -DAEMU_MTK -DAEMU_PCM_DEV='"/dev/aemu_pcm"' \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,-soname,libaudio.primary.default.so \
  -o "$(dirname "$OUT")/audio.primary.mtk.so" audio_hal.c -ldl -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/audio.primary.mtk.so" 2>/dev/null || true
 
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -mfloat-abi=softfp -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE -DAEMU_FMVOL \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,-soname,audio.primary.default.so \
  -o "$(dirname "$OUT")/audio.primary.fmvol.so" audio_hal.c -lc
"$BIN/llvm-strip" "$(dirname "$OUT")/audio.primary.fmvol.so" 2>/dev/null || true
