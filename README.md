# Socrates V9.3 — Android Studio debug build

This package combines your Socrates V9.3 locked-starting-volume UI (with the new smiling Socrates mascot in the header and Settings) with the previous V9.2 native Android alarm/playback implementation.

## Build on your Linux Mint laptop

Open the extracted `Socrates-V9.3-AndroidStudio` project folder in Android Studio, allow Gradle sync, and select **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or run in Terminal:

```bash
cd ~/Downloads/Socrates-V9.3-AndroidStudio
chmod +x gradlew build-apk.sh
./build-apk.sh
```

For the wrapper JAR, this script tries to copy from `~/AndroidStudioProjects/MusicFolderAlarm/gradle/wrapper/gradle-wrapper.jar` or `~/AndroidStudioProjects/MusicAlarm/gradle/wrapper/gradle-wrapper.jar`. If necessary, manually copy your existing wrapper JAR to this project at `gradle/wrapper/gradle-wrapper.jar`.

**Output:** `~/Downloads/Socrates-V9.3-debug.apk`, or `app/build/outputs/apk/debug/app-debug.apk` inside the project.

## What changed

- V9.3 HTML UI with Socrates icon in the header, Settings/About screen, and browser favicon.
- Android launcher icon/round icon across all standard screen densities.
- Android app label **Socrates**; versionCode **12**, versionName **9.3**.
- Retains application ID `com.musicfolderalarm.app` to preserve compatibility with the prior install and its data. Do not uninstall the old app before upgrading.
- Carries forward the V9.2 native backend for exact alarms, background playback, foreground service, SAF audio folder indexing, large libraries, and locked starting volume.

## Important

This is a **build-ready Android Studio source archive, not a compiled APK**. It has not been compiled or tested on a physical Android device in the generating environment. Android SDK API 35, Android Gradle Plugin 8.7.3, Gradle 8.9, and JDK 17+ are required. The device must allow alarm permissions and the selected music folder must remain accessible. Android doesn't provide a universal 100% alarm firing guarantee due to device-specific battery/process restrictions; test on your phone for at least one hour idle and following reboot.

Because the version number changed, updating over your previous install is possible if it has the *same application ID and signing key*. A differently signed APK will fail to install over the old one. Debug builds on different machines can use different debug signing keys; avoid uninstalling until you have backed up alarm settings.
