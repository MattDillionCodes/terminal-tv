#!/bin/zsh
# Build Matrix (R8-shrunk release), install it on the TCL and open it, for testing.
# The TCL is a test device: Matrix is only ever an app opened there, never its Home. Installing a
# second Home app can clear the TV's saved Home choice (and with the Google launcher disabled that
# leaves a "choose a home app" picker), so whatever Home the TV had before is put back afterwards.
# Usage: ./install-tv.sh            (TV=ip:port to override)
set -e
TV=${TV:-192.168.68.52:5555}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}

home() { $ADB shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r'; }

cd "${0:A:h}"
./gradlew assembleRelease --console=plain -q
$ADB connect "$TV" >/dev/null
export ANDROID_SERIAL=$TV
before=$(home)
$ADB install -r app/build/outputs/apk/release/app-release.apk

case "$before" in
  */*ResolverActivity|"") echo "Home was not set before; leaving it as it is" ;;
  */*) $ADB shell cmd package set-home-activity "$before" >/dev/null ;;
esac
echo "Home: $(home)"
$ADB shell am start -n com.matthew.matrixhome/.HomeActivity >/dev/null
