#!/bin/sh
 
 
 
 
set -e
here=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_JAR:?set ANDROID_JAR to an android-21 (or newer) android.jar}"
 
DX=${DX:-$(command -v dalvik-exchange || command -v dx || true)}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"
 
javac --release 8 -nowarn -Xlint:-options -cp "$ANDROID_JAR" -d "$work/classes" \
    $(find "$here/java" "$here/stubs" -name '*.java')
find "$work/classes" -path '*/android' -prune -exec rm -rf {} + 2>/dev/null || true
if [ -n "${D8_JAR:-}" ]; then
    (cd "$work/classes" && java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 19 --lib "$ANDROID_JAR" --output "$work/dex" $(find . -name "*.class"))
else
    "$DX" --dex --min-sdk-version=19 --output="$work/dex/classes.dex" "$work/classes"
fi
out="$here/../src/main/assets/engines/kk/aemu-wifi.jar"
(cd "$work/dex" && rm -f "$out" && zip -q -X "$out" classes.dex)
echo "wrote $out"
