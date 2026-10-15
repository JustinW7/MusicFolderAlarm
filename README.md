# Music Folder Alarm V9.2 — Large Library / 1,000+ tracks

**This ZIP is full Android Studio SOURCE, not a compiled APK.** This environment has Java but no Android SDK, Gradle distribution, or Android Gradle Plugin cache; native Android compilation and phone tests could not be run here.

This is a revised V9 build derived from the V9.1 TransactionTooLargeException fix. Changes:
- Removed the previous hard 1,500-track cap from FolderScanner. Recursive scanning supports depths up to 64 (cycle protection).
- Added TrackLibrary.java: per-folder disk-backed index of scanned audio files; alarm playback reads the cached index instead of rescanning the full folder at trigger time. Selecting a folder again refreshes its cached index.
- WebView receives folder track metadata in pages of 200 tracks rather than a multi-megabyte injected script.
- Folder/Now Playing search and lists render only up to 80 tracks at a time; Load more shows subsequent 80 without constructing 10,000 DOM nodes.
- Native service starts in foreground before parsing a large manual-play queue, and processes the JSON queue off the main thread.
- Smart-shuffle starting-track history uses a HashSet to avoid quadratic time for 10,000 songs.
- Queues are passed via a private file, not Binder Intents. Player state broadcasts include only the current track.
- Preserves the existing alarm-clock scheduling, background playback, permissions UI, three media notification buttons and previous MediaPlayer state fix.

Limitations: This is not a certified 10,000-track build. Scanner performance depends on the phone, storage provider, nesting, and file accessibility; 1,000/5,000/10,000-track device tests have not been performed. Newly selected huge folders may take time to scan the first time. If a user adds/removes songs, select the folder again to refresh its index. Music selection and playback require persisted Storage Access Framework access. Keep a backup alarm for important wakeups.

## Build on your Linux Mint system — no Android Studio window needed

Unzip to ~/AndroidStudioProjects/MusicFolderAlarm_V9_2_LargeLibrary_AndroidStudio.

    cd ~/AndroidStudioProjects/MusicFolderAlarm_V9_2_LargeLibrary_AndroidStudio
    chmod +x gradlew build-apk.sh
    ./gradlew assembleDebug --max-workers=2 --console=plain
    cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/MusicFolderAlarm_V9_2_LargeLibrary.apk

The wrapper automatically copies its JAR from ~/AndroidStudioProjects/MusicFolderAlarm/gradle/wrapper/ if needed. Requires Android SDK API 35, internet for any uncached Gradle dependencies, and JDK >=17. You already have these on your Linux Mint machine from the previous builds.

App ID is com.musicfolderalarm.app; versionCode=11. Install over existing V9 (signed with same signing key), do not uninstall unless data has been backed up. On Android, scan folder initially while app open before the next alarm, then set a test alarm and verify sound.

## Regression tests
1. Select folder with 1,000+ audio tracks, wait for indexing, see correct count.
2. Shuffle and play any track; previous/play-pause/next from Android notification drawer.
3. Search tracks by name without UI freezing; Load more rows.
4. Create an alarm with this folder, lock screen for 1 hour, verify audible alarm and History event.
5. Repeat with ~5,000 tracks and overnight, and after reboot.
6. Check logs for `TransactionTooLargeException` and `MediaPlayer` issues.

Use:
    adb logcat -v time | grep -iE 'MusicPlaybackService|MusicTrackLibrary|TransactionTooLarge|MediaPlayer|MusicAlarmReceiver'
