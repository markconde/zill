#!/bin/sh
# Builds the release APK and publishes it to a self-hosted F-Droid repo over SSH.
# Where it goes is configuration, kept outside the repo: ~/.config/sill-android/fdroid.conf
# (or the file $FDROID_CONFIG names), a shell file setting
#   FDROID_HOST       SSH host of the F-Droid server
#   FDROID_REPO_DIR   the repo folder on that host, where the APKs go
#   FDROID_UPDATE     the command on that host that refreshes the repo index (e.g. `fdroid update`)
#   FDROID_URL        the repo's address, only for the message at the end (optional)
# Bump versionCode in app/build.gradle.kts first: phones only update to a higher one.
set -e
cd "$(dirname "$0")"

: "${JAVA_HOME:=$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export JAVA_HOME
CONF=${FDROID_CONFIG:-$HOME/.config/sill-android/fdroid.conf}
[ -f "$CONF" ] || { echo "No F-Droid config at $CONF (see the top of this script)." >&2; exit 1; }
. "$CONF"
for v in FDROID_HOST FDROID_REPO_DIR FDROID_UPDATE; do
    eval "[ -n \"\${$v}\" ]" || { echo "$CONF doesn't set $v." >&2; exit 1; }
done
APP=dev.mrkcnd.zill
KEY=${SILL_KEYSTORE_PROPERTIES:-$HOME/.config/sill-android/keystore.properties}

[ -f "$KEY" ] || { echo "No release key at $KEY: F-Droid updates need the same key every time." >&2; exit 1; }
CODE=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
NAME=$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -n "$CODE" ] || { echo "No versionCode in app/build.gradle.kts" >&2; exit 1; }
if ssh "$FDROID_HOST" "test -e $FDROID_REPO_DIR/${APP}_$CODE.apk -o -e $FDROID_REPO_DIR/../archive/${APP}_$CODE.apk"; then
    echo "versionCode $CODE is already published. Bump it in app/build.gradle.kts." >&2; exit 1
fi

./gradlew -q testDebugUnitTest assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
scp -q "$APK" "$FDROID_HOST:$FDROID_REPO_DIR/${APP}_$CODE.apk"
ssh "$FDROID_HOST" "$FDROID_UPDATE" 2>&1 | grep -E "ERROR|Finished" || true
echo "Published $NAME ($CODE)${FDROID_URL:+ to $FDROID_URL}. Pull to refresh in F-Droid to see it."
