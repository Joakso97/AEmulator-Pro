#!/bin/sh
 
set -eu
cd "$(dirname "$0")"
gcc -O1 -g -Wall -Wno-unused-function -o /tmp/solo-smoke solo-smoke.c -ldl -lpthread
/tmp/solo-smoke
