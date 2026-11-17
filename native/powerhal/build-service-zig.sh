#!/bin/sh
 
 
 
 
 
 
 
 
 
 
set -e
ZIG=${ZIG:-zig}
command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
cd "$(dirname "$0")"
ENGINES=../../app/src/main/assets/engines
NAME='vendor#bin#hw#android.hardware.power-service.aemu'
OUT=${1:-$ENGINES/n64-37/$NAME}
mkdir -p "$(dirname "$OUT")"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TARGET="-target aarch64-linux-android"

 
LIBC_FUNCS="write __libc_init"
BINDER_FUNCS="AIBinder_Class_define AIBinder_new AIBinder_markVintfStability AServiceManager_addService
  ABinderProcess_setThreadPoolMaxThreadCount ABinderProcess_joinThreadPool AParcel_writeInt32 AParcel_writeInt64
  AParcel_writeBool AParcel_writeString AParcel_getDataPosition AParcel_getDataSize AParcel_setDataPosition AParcel_writeStatusHeader
  AStatus_newOk AStatus_fromExceptionCode AStatus_delete"
for f in $LIBC_FUNCS; do echo "void $f(void) { for (;;) {} }"; done > "$T/libc_stub.c"
for f in $BINDER_FUNCS; do echo "void $f(void) {}"; done > "$T/libbinder_ndk_stub.c"
for n in c binder_ndk; do
  $ZIG cc $TARGET -w -fno-builtin -fno-sanitize=undefined -nostdlib -shared -fPIC -Wl,-soname,lib$n.so -Wl,--build-id=none \
      -o "$T/lib$n.so" "$T/lib${n}_stub.c"
done

$ZIG cc $TARGET -O2 -fPIE -fno-sanitize=undefined -ffreestanding -nostdlib -fno-stack-protector -fno-unwind-tables \
  -fno-asynchronous-unwind-tables -Wall -Wextra -Wno-unused-parameter -pie -Wl,--dynamic-linker=/system/bin/linker64 \
  -Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,now -Wl,-z,relro -Wl,-z,max-page-size=16384 -Wl,--gc-sections \
  -Wl,-e,_start -Wl,-s \
  -o "$OUT" power_service.c "$T/libbinder_ndk.so" "$T/libc.so"
echo "built $OUT ($(wc -c < "$OUT") bytes)"
 
if [ -z "$1" ]; then
  for k in 35 36; do
    D="$ENGINES/n64-$k"; mkdir -p "$D"; cp "$OUT" "$D/$NAME"
    echo "copied to $D/$NAME"
  done
fi
