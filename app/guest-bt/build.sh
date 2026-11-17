#!/bin/sh
 
 
 
 
 
 
set -e
here=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_JAR:?set ANDROID_JAR to an android-19 android.jar}"
DX=${DX:-$(command -v dalvik-exchange || command -v dx)}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/gen" "$work/classes" "$work/dex"
for f in "$here"/aidl/android/bluetooth/I*.aidl; do aidl -I"$here/aidl" -o"$work/gen" "$f"; done
javac --release 8 -nowarn -Xlint:-options -cp "$ANDROID_JAR" -sourcepath "$work/gen" -d "$work/classes" \
    $(find "$here/java" -name '*.java')
 
find "$work/classes" -path '*/android' -prune -exec rm -rf {} + 2>/dev/null || true
"$DX" --dex --min-sdk-version=19 --output="$work/dex/classes.dex" "$work/classes"
out="$here/../src/main/assets/engines/kk/aemu-bt.jar"
(cd "$work/dex" && rm -f "$out" && zip -q -X "$out" classes.dex)
echo "wrote $out"
