#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  for old in "$HOME/AndroidStudioProjects/MusicFolderAlarm/gradle/wrapper/gradle-wrapper.jar" "$HOME/AndroidStudioProjects/MusicAlarm/gradle/wrapper/gradle-wrapper.jar"; do
    if [ -f "$old" ]; then cp "$old" "$JAR"; break; fi
  done
fi
if [ ! -f "$JAR" ]; then
  printf '%s\n' 'Gradle wrapper JAR is missing.' \
    'Copy gradle-wrapper.jar from your existing Android Studio project into gradle/wrapper/.' \
    'Or open this folder in Android Studio and configure Gradle from the IDE.'
  exit 2
fi
exec java -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
