#!/bin/sh
APP_HOME=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
export GRADLE_USER_HOME="$APP_HOME/.gradle-user-home"
export ANDROID_USER_HOME="$APP_HOME/.local/android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export TMPDIR="$APP_HOME/.local/tmp"
mkdir -p "$TMPDIR" "$ANDROID_USER_HOME" || exit 1
if [ -n "$JAVA_HOME" ]; then JAVA_EXE="$JAVA_HOME/bin/java"; else JAVA_EXE=java; fi
cd "$APP_HOME" || exit 1
exec "$JAVA_EXE" "-Djava.io.tmpdir=$TMPDIR" "-Duser.home=$APP_HOME/.local" "-Dgradle.user.home=$GRADLE_USER_HOME" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
