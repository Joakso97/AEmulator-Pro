#!/bin/sh
 
 
 
 
 
 
 
 
 
 
 
 
 
 
set -eu
H=$(cd "$(dirname "$0")" && pwd)
A=${1:-$H/aosp12l}; A=$(cd "$A" && pwd)
OUT=${2:-$H/../../../app/src/main/assets/engines/common-x86_64}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
[ -f "$A/gen/android/hardware/graphics/allocator/2.0/IAllocator.h" ] || { echo "run fetch-aosp.sh first" >&2; exit 1; }
 
LB=$A/system_libbase/include; [ -d "$LB" ] || LB=$A/system_core/base/include
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TG="-target x86_64-linux-android"
HI=$A/hardware_interfaces/graphics

 
 
ZL=$($ZIG env | sed -n 's/.*\.lib_dir = "\(.*\)".*/\1/p; s/.*"lib_dir": *"\([^"]*\)".*/\1/p')
mkdir -p "$T/clang-inc" "$T/cfg"; cp -r "$ZL"/include/* "$T/clang-inc/"; rm -f "$T/clang-inc/stdint.h" "$T/clang-inc/inttypes.h" "$T/clang-inc/stdatomic.h"
printf '#ifndef _LIBCPP_CONFIG_SITE\n#define _LIBCPP_CONFIG_SITE\n#define _LIBCPP_HAS_THREAD_API_PTHREAD\n#endif\n' > "$T/cfg/__config_site"
INC="-nostdinc -nostdinc++ -isystem $T/cfg -isystem $A/libcxx/include -isystem $A/libcxxabi/include -isystem $T/clang-inc
 -I$A/gen -I$HI/allocator/2.0/utils/hal/include -I$HI/allocator/2.0/utils/passthrough/include
 -I$HI/mapper/2.0/utils/hal/include -I$HI/mapper/2.0/utils/passthrough/include
 -I$A/system_libhidl/base/include -I$A/system_libhidl/transport/include
 -I$A/system_core/libutils/include -I$A/system_core/libcutils/include -I$A/system_core/libsync/include -I$A/system_core/libsystem/include
 -I$A/system_core/include -I$LB -I$A/system_logging/liblog/include -I$A/hardware_libhardware/include
 -I$A/system_libhwbinder/include -I$A/system_libfmq/base -I$A/system_libfmq/include
 -isystem $A/bionic/libc/include -isystem $A/bionic/libc/kernel/uapi -isystem $A/bionic/libc/kernel/uapi/asm-x86 -isystem $A/bionic/libc/kernel/android/uapi"
CXX="$ZIG c++ $TG -std=c++17 -fPIC -fno-exceptions -fno-rtti -fno-stack-protector -Os -w -D__ANDROID_API__=${ANDROID_API:-32} -D_LIBCPP_HAS_THREAD_API_PTHREAD"
CC="$ZIG cc $TG -Os -fPIC -w -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables"
LD="$ZIG c++ $TG -nostdlib"
LDF="-Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,norelro -Wl,-z,max-page-size=4096 -Wl,--no-as-needed -Wl,-s"

 
$CXX $INC -DLOG_TAG='"AllocatorHal"' -c $HI/allocator/2.0/default/passthrough.cpp -o $T/alloc_impl.o
$CXX $INC -DLOG_TAG='"MapperHal"' -c $HI/mapper/2.0/default/passthrough.cpp -o $T/mapper_impl.o
$CXX $INC -DLOG_TAG='"android.hardware.graphics.allocator@2.0-service"' -c $HI/allocator/2.0/default/service.cpp -o $T/svc.o
for f in gralloc framebuffer mapper; do
    $CXX $INC -x c++ -DLOG_TAG='"gralloc"' -Wno-missing-field-initializers -c $A/hardware_libhardware/modules/gralloc/$f.cpp -o $T/ga_$f.o
done
$CC -c $H/crt_begin.c -o $T/crt_begin.o; $CC -c $H/crt_end.c -o $T/crt_end.o; $CC -c $H/dso_handle.c -o $T/dso_handle.o

 
mkdir -p $T/so
standin() {      
    f=$T/so/$1; : > $T/e.c
    [ -n "${2:-}" ] && while read -r s; do printf 'void %s(void){}\n' "$s"; done < "$2" > $T/e.c
    $CC -nostdlib -shared -Wl,-soname,$1 -Wl,--build-id=none -o $f $T/e.c
}
for L in libdl.so libm.so liblog.so libcutils.so libhardware.so libutils.so libbase.so libhidlbase.so libc++.so libsync.so \
         android.hardware.graphics.allocator@2.0.so android.hardware.graphics.mapper@2.0.so; do standin $L; done
nm -u $T/svc.o $T/crt_begin.o | awk '$1=="U"{print $2}' | sort -u | grep -v '^main$' > $T/svc.undef
standin libc.so $T/svc.undef; mv $T/so/libc.so $T/so/libc_svc.so; standin libc.so
S=$T/so
BASE="$S/libc++.so $S/libm.so $S/libdl.so $S/libc.so"

 
$LD -shared -Wl,-soname,android.hardware.graphics.allocator@2.0-impl.so $LDF $T/alloc_impl.o $T/dso_handle.o \
    $S/android.hardware.graphics.allocator@2.0.so $S/libbase.so $S/libcutils.so $S/libhardware.so $S/libhidlbase.so $S/liblog.so $S/libutils.so $BASE \
    -o "$OUT/vendor#lib64#hw#android.hardware.graphics.allocator@2.0-impl.so"
$LD -shared -Wl,-soname,android.hardware.graphics.mapper@2.0-impl.so $LDF $T/mapper_impl.o $T/dso_handle.o \
    $S/android.hardware.graphics.mapper@2.0.so $S/libbase.so $S/libcutils.so $S/libhardware.so $S/libhidlbase.so $S/liblog.so $S/libsync.so $S/libutils.so $BASE \
    -o "$OUT/vendor#lib64#hw#android.hardware.graphics.mapper@2.0-impl.so"
$LD -shared -Wl,-soname,libgralloc_aosp.so $LDF $T/ga_gralloc.o $T/ga_framebuffer.o $T/ga_mapper.o $T/dso_handle.o \
    $S/liblog.so $S/libcutils.so $BASE -o "$OUT/vendor#lib64#libgralloc_aosp.so"
$LD -pie -Wl,-dynamic-linker=/system/bin/linker64 $LDF $T/crt_begin.o $T/svc.o $T/crt_end.o \
    $S/android.hardware.graphics.allocator@2.0.so $S/libhidlbase.so $S/liblog.so $S/libutils.so $S/libc++.so $S/libm.so $S/libdl.so $T/so/libc_svc.so \
    -o "$OUT/vendor#bin#hw#android.hardware.graphics.allocator@2.0-service"
 
 
ZB_BY_PATH=1 sh "$H/../zig-bionic.sh" "$OUT/vendor#lib64#hw#gralloc.default.so" gralloc.default.so "$H/../../grallocwrap/gralloc_wrap.c" -DAEMU_META

 
 
for f in "$OUT/vendor#bin#hw#android.hardware.graphics.allocator@2.0-service"; do
    readelf -lW "$f" | grep -q ' INTERP ' || { echo "error: $f has no PT_INTERP" >&2; exit 1; }
done
 
for f in "$OUT"/vendor#lib64#hw#android.hardware.graphics.*-impl.so "$OUT"/vendor#lib64#libgralloc_aosp.so "$OUT/vendor#bin#hw#android.hardware.graphics.allocator@2.0-service" "$OUT/vendor#lib64#hw#gralloc.default.so"; do
    if nm -D "$f" | grep -Eq ' U (__stack_chk_fail|__errno_location|__memcpy_chk|__sprintf_chk|__snprintf_chk|__fprintf_chk)$'; then echo "error: $f imports a glibc/musl-only symbol" >&2; exit 1; fi
done
ls -l "$OUT"/vendor#*graphics* "$OUT"/vendor#lib64#libgralloc_aosp.so "$OUT"/vendor#lib64#hw#gralloc.default.so
