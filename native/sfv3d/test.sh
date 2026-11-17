#!/bin/sh
 
set -e
cd "$(dirname "$0")"
${CROSS:-arm-linux-gnueabi}-gcc -mthumb -march=armv7-a -O1 -static -Wall -o /tmp/test_v3dsoft test_v3dsoft.c
qemu-arm /tmp/test_v3dsoft
