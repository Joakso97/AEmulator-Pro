#!/bin/sh
 
 
 
 
 
 
 
 
 
set -eu
OUT=${1:?out.so}; SONAME=${2:?soname}; SRC=${3:?source.c}; shift 3
ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TG="--target=x86_64-linux-musl"
CF="-Os -fPIC -fvisibility=hidden -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables -ffreestanding -fno-builtin -I$HERE/inc -Wall -Wno-unused-function"
$ZIG cc $TG $CF "$@" -c "$SRC" -o "$T/o.o"
nm -u "$T/o.o" | awk '$1=="U"||$1=="w"{print $2}' | sort -u > "$T/undef"
 
if grep -E '^(__stack_chk_fail|__errno_location|__clock_gettime64|__memcpy_chk|__sprintf_chk|__snprintf_chk|__fprintf_chk|__vfprintf_chk|__printf_chk)$' "$T/undef" >&2; then
  echo "error: $SRC imports a musl-only symbol (above)" >&2; exit 1
fi
mkdir -p "$(dirname "$OUT")"
 
LIBM='^(sin|cos|sinf|cosf|sqrt|sqrtf|floor|floorf|ceil|ceilf|pow|powf|fabs|fabsf|fmod|fmodf|roundf|round|lroundf|lround)$'
LIBDL='^(dlopen|dlsym|dlclose|dlerror|dladdr)$'
LIBLOG='^__android_log_'
LIBHW='^(hw_get_module|hw_get_module_by_class)$'
LIBCUTILS='^(property_get|property_set|native_handle_.*|ashmem_.*)$'
for L in c log dl m hardware; do : > "$T/$L.syms"; done
while read -r s; do
  case "$s" in
    _GLOBAL_OFFSET_TABLE_|_DYNAMIC) continue;;
  esac
  if   echo "$s" | grep -Eq "$LIBLOG"; then echo "$s" >> "$T/log.syms"
  elif echo "$s" | grep -Eq "$LIBDL";  then echo "$s" >> "$T/dl.syms"
  elif echo "$s" | grep -Eq "$LIBM";   then echo "$s" >> "$T/m.syms"
  elif echo "$s" | grep -Eq "$LIBHW";  then echo "$s" >> "$T/hardware.syms"
  else echo "$s" >> "$T/c.syms"; fi
done < "$T/undef"
NEED=""
for L in c log dl m hardware; do
  [ -s "$T/$L.syms" ] || continue
  while read -r s; do printf "void %s(void) {}\n" "$s"; done < "$T/$L.syms" > "$T/$L.c"
  $ZIG cc $TG -nostdlib -shared -fPIC -w -Wl,-soname,lib$L.so -Wl,--build-id=none -o "$T/lib$L.so" "$T/$L.c"
   
   
  if [ -n "${ZB_BY_PATH:-}" ]; then NEED="$NEED $T/lib$L.so"; else NEED="$NEED -l$L"; fi
done
 
 
 
$ZIG cc $TG -nostdlib -shared $CF "$@" -Wl,-soname,"$SONAME" -Wl,-z,norelro -Wl,-z,now -Wl,--build-id=none -Wl,--hash-style=both \
  -Wl,--no-undefined -Wl,-s -Wl,--no-as-needed -Wl,-z,max-page-size=4096 -L"$T" -o "$OUT" "$T/o.o" $NEED
ls -l "$OUT"
