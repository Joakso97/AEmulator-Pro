#!/bin/sh
 
 
set -eu
H=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$H/../../app/src/main/assets/engines/common-x86_64/vendor#lib64#hw#sensors.default.so}
sh "$H/zig-bionic.sh" "$OUT" sensors.default.so "$H/sensors_none.c"
