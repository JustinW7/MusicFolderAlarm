<<<<<<< HEAD
# Music Folder Alarm — playback correction for Realme C30

This patch changes only `MusicPlaybackService.kt` and adds a bundled backup alarm sound (`res/raw/music_alarm_fallback.wav`). All UI screens, saved alarms, history, schedules, and original `/home/justin/AndroidStudioProjects/MusicAlarm` are left untouched.

## What the photographed History demonstrates

1. The scheduled alarm was delivered and 1,074 audio document entries were discovered.
2. The initial selected music file failed to load.
3. The original source passed `RingtoneManager.getDefaultUri(TYPE_ALARM)` to ExoPlayer. That returned a settings URI that also failed on the phone (`ERROR_CODE_IO_FILE_NOT_FOUND`).

## Changes

- Tries the next track when a track URI is unavailable. Up to 25 failed tracks are skipped per alarm before the backup starts.
- The alarm's Smart Shuffle starting track is honored by explicitly ordering the playlist.
- Replaces the system settings alarm URI with a **bundled WAV** through Android `MediaPlayer`, looping with alarm audio usage. This does not rely on any external file, ringtone permission, or provider.
- History records actual playing instead of claiming success merely after ExoPlayer.prepare()/play().
- When manual playback starts, the backup alarm tone is stopped.

## Install

In Terminal:

```bash
cd ~/Downloads
unzip -o MusicFolderAlarm-PlaybackFix.zip
bash MusicFolderAlarm-PlaybackFix/install.sh
```

Open MusicFolderAlarm in Android Studio, choose RMX3581 (not emulator), click Run.

## Test

1. Keep current saved alarms; do not uninstall or clear data.
2. Create a new alarm for two minutes from now, selecting a folder that contains ordinary MP3 files.
3. Lock your Realme C30 and wait.
4. Check History: `Music is playing successfully` means selected music worked; `Bundled alarm tone playing` means fallback is now sounding, but selected tracks remain inaccessible.
5. In the second case, use Player > Folders and manually try a known local MP3. Then share the new History entry and whether manual playback worked.

## Notes

This is a source patch, not an APK. Not compiled on user's Android SDK/device in this environment. An unresponsive file provider, missing files, app force-stop, or OEM background restrictions can still prevent normal music playback or scheduled service launch. Do not rely on an unverified alarm as your only alarm.
=======
# MusicFolderAlarm
Personal utility
>>>>>>> origin/main
