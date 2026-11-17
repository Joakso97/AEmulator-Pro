#!/bin/sh
 
 
 
 
set -eu
ASSETS=${1:-../../app/src/main/assets/engines}
CC=${CC:-gcc}
 
 
 
 
STUB=$(mktemp -d)
trap 'rm -rf "$STUB"' EXIT
printf 'int *__errno(void) { static int e; return &e; }\n' > "$STUB/libc.c"
$CC -m32 -Os -fPIC -shared -nostdlib -ffreestanding -fno-stack-protector -Wl,--hash-style=sysv -Wl,--build-id=none \
    -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/libc.c"
build() {    
    mkdir -p "$(dirname "$2")"
    $CC -m32 -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -fvisibility=hidden \
        -fno-tree-loop-distribute-patterns -fno-asynchronous-unwind-tables -fno-unwind-tables \
        -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,-z,noexecstack -Wl,--build-id=none -Wl,-soname,"$3" -Wl,--no-undefined -o "$2" "$1" "$STUB/libc.so"
    ${STRIP:-strip} --strip-unneeded "$2"
}
build ashmem_x86.c   "$ASSETS/kk-x86/libashmemshim.so"      libashmemshim.so
build aemushim_x86.c "$ASSETS/common-x86/libaemushim.so"    libaemushim.so
 
mkdir -p "$ASSETS/common-x86"
${AS:-as} --32 true_x86.S -o /tmp/aemu_true_x86.o
${LD:-ld} -m elf_i386 -static -nostdlib --build-id=none -z noexecstack -o "$ASSETS/common-x86/aemu_true.so" /tmp/aemu_true_x86.o
${STRIP:-strip} "$ASSETS/common-x86/aemu_true.so"
rm -f /tmp/aemu_true_x86.o
