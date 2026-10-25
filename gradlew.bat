@echo off
setlocal
set "APP_HOME=%~dp0"
rem Keep generated files inside this workspace, including the debug keystore.
set "GRADLE_USER_HOME=%APP_HOME%.gradle-user-home"
set "ANDROID_USER_HOME=%APP_HOME%.local\android-user"
set "ANDROID_EMULATOR_HOME=%APP_HOME%.local\android-user"
set "TEMP=%APP_HOME%.local\tmp"
set "TMP=%TEMP%"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"
if defined JAVA_HOME (set "JAVA_EXE=%JAVA_HOME%\bin\java.exe") else (set "JAVA_EXE=java.exe")
pushd "%APP_HOME%"
"%JAVA_EXE%" "-Djava.io.tmpdir=%TEMP%" "-Duser.home=%APP_HOME%.local" "-Dgradle.user.home=%GRADLE_USER_HOME%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
