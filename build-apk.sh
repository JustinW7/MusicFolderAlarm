#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -d "$HOME/Android/Sdk" ]]; then
  export ANDROID_HOME="$HOME/Android/Sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi
chmod +x gradlew
./gradlew assembleDebug --max-workers=2 --console=plain
APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then echo "No valid built APK found" >&2;exit 1;fi
cp "$APK" "$HOME/Downloads/MusicFolderAlarm_V9_2_LargeLibrary.apk"
ls -lh "$APK" "$HOME/Downloads/MusicFolderAlarm_V9_2_LargeLibrary.apk"
