#!/bin/sh
 
 
set -eu
H=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
${CXX:-g++} -std=c++17 -Wall -I"$H" "$H/tests/health-host-smoke.cpp" -o "$T/smoke"
"$T/smoke"
