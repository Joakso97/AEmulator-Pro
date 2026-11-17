#!/bin/sh
 
 
set -eu
H=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$H/../../app/src/main/assets/engines/common-x86_64/vendor#lib64#hw#hwcomposer.default.so}
ZB_BY_PATH=1 sh "$H/zig-bionic.sh" "$OUT" hwcomposer.default.so "$H/../hwcomposer/hwcomposer.c"
