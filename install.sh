#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:-$HOME/AndroidStudioProjects/MusicFolderAlarm}"
SOURCE="$(cd -- "$(dirname -- "$0")" && pwd)"

if [[ "$(basename -- "$TARGET")" != "MusicFolderAlarm" ]]; then
  echo "Refusing to install: target must be MusicFolderAlarm, not MusicAlarm." >&2
  exit 2
fi
if [[ ! -f "$TARGET/app/build.gradle.kts" ]] || [[ ! -d "$TARGET/app/src/main/java/com/musicalarm/controller" ]]; then
  echo "Cannot find MusicFolderAlarm Android Studio project at: $TARGET" >&2
  exit 2
fi

BACKUP="$(dirname -- "$TARGET")/MusicFolderAlarm-Playback-Backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP"
for relative in \
  'app/src/main/java/com/musicalarm/controller/MusicPlaybackService.kt' \
  'app/src/main/res/raw/music_alarm_fallback.wav'; do
  src="$SOURCE/$relative"
  dst="$TARGET/$relative"
  if [[ -f "$dst" ]]; then
    mkdir -p "$BACKUP/$(dirname -- "$relative")"
    cp -p "$dst" "$BACKUP/$relative"
  fi
  mkdir -p "$(dirname -- "$dst")"
  cp -- "$src" "$dst"
done

echo "Installed playback fix in: $TARGET"
echo "Backup at: $BACKUP"
echo "Existing alarms, History, UI, and the original MusicAlarm project are untouched."
echo "Now select RMX3581 and click Run in Android Studio."
