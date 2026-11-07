#!/bin/sh
# Builds the release APK and publishes it to the F-Droid repo on the server
# (https://fdroid.example.com/fdroid/repo; see /path/to/fdroid/README.md there).
# Bump versionCode in app/build.gradle.kts first: phones only update to a higher one.
set -e
cd "$(dirname "$0")"

: "${JAVA_HOME:=$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export JAVA_HOME
HOST=${FDROID_HOST:-your-fdroid-host}
REPO=/path/to/fdroid/fdroid/repo
APP=dev.mrkcnd.sillclient
KEY=${SILL_KEYSTORE_PROPERTIES:-$HOME/.config/sill-android/keystore.properties}

[ -f "$KEY" ] || { echo "No release key at $KEY: F-Droid updates need the same key every time." >&2; exit 1; }
CODE=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
NAME=$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -n "$CODE" ] || { echo "No versionCode in app/build.gradle.kts" >&2; exit 1; }
if ssh "$HOST" "test -e $REPO/${APP}_$CODE.apk -o -e $REPO/../archive/${APP}_$CODE.apk"; then
    echo "versionCode $CODE is already published. Bump it in app/build.gradle.kts." >&2; exit 1
fi

./gradlew -q testDebugUnitTest assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
scp -q "$APK" "$HOST:$REPO/${APP}_$CODE.apk"
ssh "$HOST" /path/to/fdroid/update.sh 2>&1 | grep -E "ERROR|Finished" || true
echo "Published $NAME ($CODE). Pull to refresh in F-Droid to see it."
