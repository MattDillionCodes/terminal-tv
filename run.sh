#!/bin/zsh
# Build Matrix Home and run it on an Android TV emulator as the Home app (never touches a real TV).
# Finds the emulator by AVD name, not "the first one", since other launchers' emulators may be up too.
# Usage: ./run.sh            (AVD=name picks a different emulator)
set -e
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
AVD=${AVD:-Matrix_TV}
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}

cd "${0:A:h}"
find_serial() {
  for s in $($ADB devices | awk '/^emulator-/ { print $1 }'); do
    [ "$($ADB -s $s emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ] && { echo $s; return; }
  done
}
serial=$(find_serial)
if [ -z "$serial" ]; then
  echo "booting $AVD..."
  $SDK/emulator/emulator -avd "$AVD" -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
  until serial=$(find_serial); [ -n "$serial" ]; do sleep 2; done
  until [ "$($ADB -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
fi
export ANDROID_SERIAL=$serial

./gradlew assembleDebug --console=plain -q
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
# The stock Google TV launcher declares a higher HOME priority and can win even after
# set-home-activity, so it's disabled on the emulator (re-enable: pm enable com.google.android.tvlauncher).
$ADB shell pm disable-user --user 0 com.google.android.tvlauncher >/dev/null 2>&1 || true
$ADB shell cmd package set-home-activity com.matthew.matrixhome/.HomeActivity
$ADB shell input keyevent KEYCODE_HOME
echo "Matrix Home is running on $serial ($AVD)"
