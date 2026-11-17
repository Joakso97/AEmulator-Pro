#!/bin/sh
 
exec sh "$(dirname "$0")/build-x86-zig.sh" i386 "$@"
