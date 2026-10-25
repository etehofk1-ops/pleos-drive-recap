param([string]$TestJar = 'build/w16/runtime/logic-tests.jar', [string]$OutputRoot='build/w16')
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output=[IO.Path]::GetFullPath((Join-Path $workspace $OutputRoot))
if (!$output.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'OutputRoot must stay inside workspace' }
New-Item -ItemType Directory -Force "$output/home", "$output/tmp" | Out-Null
$java='C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
$stdlib='C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\lib\kotlin-stdlib.jar'
$junit=(Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/wrapper/dists/gradle-8.14.3-all" -Filter junit-4.13.2.jar -Recurse | Select-Object -First 1).FullName
$hamcrest=Join-Path (Split-Path $junit) 'hamcrest-core-1.3.jar'
$resources=Join-Path $workspace 'logic/src/test/resources'
$classpath=@((Join-Path $workspace $TestJar),$stdlib,$junit,$hamcrest,$resources)-join [IO.Path]::PathSeparator
foreach($file in Get-ChildItem "$resources/replay" -Filter '*.csv') {
    $env:RECAP_REAL_CSV=$file.FullName
    $env:RECAP_REAL_OUT=Join-Path $output "replay-$($file.BaseName).md"
    & $java "-Duser.home=$output/home" "-Djava.io.tmpdir=$output/tmp" -cp $classpath `
        org.junit.runner.JUnitCore lab.pleos.observe.logic.recap.RealDataReplayTest > "$output/replay-$($file.BaseName).log"
    Write-Output "fixture=$($file.Name) junitExit=$LASTEXITCODE"
}
