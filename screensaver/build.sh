#!/usr/bin/env bash
# Builds screensaver/build/dumbtv-saver.apk. No Gradle.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SDK=${ANDROID_HOME:-$HOME/.local/android-sdk}
JAR=$(find "$SDK/platforms" -name android.jar | head -1)
BT=$(find "$SDK/build-tools" -name aapt2 -type f | head -1)
BT=$(dirname "$BT")
[[ -f $JAR ]] || { echo "error: android.jar missing" >&2; exit 1; }
[[ -x $BT/aapt2 ]] || { echo "error: aapt2 missing" >&2; exit 1; }

if [[ -z ${JAVA_HOME:-} || ! -x ${JAVA_HOME:-}/bin/javac ]]; then
  if [[ -x $HOME/.local/jdk-17/Contents/Home/bin/javac ]]; then
    JAVA_HOME=$HOME/.local/jdk-17/Contents/Home
  elif [[ -x $HOME/.local/jdk-17/bin/javac ]]; then
    JAVA_HOME=$HOME/.local/jdk-17
  fi
fi
[[ -x ${JAVA_HOME:-}/bin/javac ]] || { echo "error: no JDK" >&2; exit 1; }
export JAVA_HOME

KEYSTORE=$PWD/release.keystore
KEYPASS=${DUMBTV_KEYPASS:-dumbtvscreensaver}
if [[ ! -f $KEYSTORE ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias saver -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=dumbtv screensaver" >/dev/null
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-saver.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" build

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28 \
  --version-code 1 --version-name 1 \
  -o "$WORK/app.apk"

find src -name '*.java' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/dumbtv-saver.apk "$WORK/aligned.apk"
"$BT/apksigner" verify build/dumbtv-saver.apk
echo "built build/dumbtv-saver.apk"
