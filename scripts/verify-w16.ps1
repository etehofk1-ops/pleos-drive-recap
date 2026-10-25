param([switch]$CompileOnly)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verification=Join-Path $workspace 'build/w16'
New-Item -ItemType Directory -Force "$verification/home", "$verification/tmp", "$verification/android-user" | Out-Null
$java='C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
$keytool=Join-Path (Split-Path $java) 'keytool.exe'
$key=Join-Path $verification 'debug.keystore'
if (!(Test-Path -LiteralPath $key)) {
    $ErrorActionPreference='Continue'
    & $keytool -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -dname 'CN=Verification' -keyalg RSA -keysize 2048 -validity 3650 2>&1 | Out-Null
    $ErrorActionPreference='Stop'
    if($LASTEXITCODE -ne 0){throw 'Isolated debug key generation failed'}
}
$env:ANDROID_USER_HOME="$verification/android-user"
$env:ANDROID_EMULATOR_HOME=$env:ANDROID_USER_HOME
$env:GRADLE_USER_HOME=Join-Path $workspace '.gradle-user-home'
$env:TEMP="$verification/tmp"; $env:TMP=$env:TEMP
$tasks=if($CompileOnly){@(':app:assembleDebug')}else{@(':logic:test',':app:assembleDebug')}
Push-Location $workspace
try {
    & $java "-Djava.io.tmpdir=$verification/tmp" "-Duser.home=$verification/home" "-Dgradle.user.home=$env:GRADLE_USER_HOME" `
        -classpath "$workspace/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain `
        -I scripts/realdata/w16-isolated.init.gradle -PisolatedVerification=true --offline --no-daemon --console=plain `
        --project-cache-dir "$verification/gradle-cache" @tasks
    if($LASTEXITCODE -ne 0){throw "Gradle verification failed: $LASTEXITCODE"}
} finally {Pop-Location}
