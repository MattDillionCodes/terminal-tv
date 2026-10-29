#!/bin/zsh
# Build Matrix (R8-shrunk release), install it on a TV over adb and open it.
# It's installed as an app you open, not made the TV's Home: installing a second Home app can clear
# the TV's saved Home choice (leaving a "choose a home app" picker), so whatever Home the TV had
# before is put back afterwards. To make Matrix the Home app, choose it in that picker or run
#   adb shell cmd package set-home-activity com.matthew.matrixhome/.HomeActivity
# Usage: ./install-tv.sh 192.168.1.20      (or TV=ip[:port]; enable ADB debugging on the TV first)
set -e
TV=${1:-$TV}
[ -n "$TV" ] || { echo "usage: $0 <tv-ip[:port]>"; exit 1; }
[[ $TV == *:* ]] || TV=$TV:5555
ADB=${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}
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
