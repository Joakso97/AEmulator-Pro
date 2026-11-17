#!/bin/sh
 
 
set -e
here=$(cd "$(dirname "$0")" && pwd)
clang --target=armv7a-linux-androideabi -marm -O2 -fPIC -ffreestanding -nostdlib -fuse-ld=lld \
    -shared -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-rosegment -Wl,-z,lazy -Wl,-z,max-page-size=4096 -Wl,-soname,libaemurlimit.so \
    -Wl,--build-id=none -Wl,--strip-all -o "$here/../src/main/assets/engines/common/libaemurlimit.so" "$here/rlimit_shim.c"
echo "wrote libaemurlimit.so"
