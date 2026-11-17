#!/bin/sh
 
set -e
cd "$(dirname "$0")"
CC=${CC:-cc}; command -v "$CC" >/dev/null 2>&1 || CC="python3 -m ziglang cc"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
$CC -Wall -Wextra -Wno-unused-parameter -o "$T/t" test_power_service.c
"$T/t"
