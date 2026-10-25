param([string]$NaviAarPath = '', [string]$AndroidStudioDir = 'C:\Program Files\Android\Android Studio')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDir = Join-Path $workspace '.local\w5-aar'
New-Item -ItemType Directory -Force $outputDir | Out-Null
$javap = Join-Path $AndroidStudioDir 'jbr\bin\javap.exe'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Inspect-Aar([string]$Aar, [string]$Name, [string[]]$Classes) {
    $digest = (Get-FileHash -LiteralPath $Aar -Algorithm SHA256).Hash
    "$Name SHA256=$digest" | Out-File -Encoding utf8 (Join-Path $outputDir "$Name.sha256.txt")
    $jar = Join-Path $outputDir "$Name-classes.jar"
    $zip = [IO.Compression.ZipFile]::OpenRead($Aar)
    try {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'), $jar, $true)
        $manifest = $zip.GetEntry('AndroidManifest.xml')
        if ($manifest) { [IO.Compression.ZipFileExtensions]::ExtractToFile($manifest, (Join-Path $outputDir "$Name-manifest.xml"), $true) }
    }
    finally { $zip.Dispose() }
    foreach ($className in $Classes) {
        $safe = $className.Replace('.', '_')
        & $javap "-J-Djava.io.tmpdir=$outputDir" -classpath $jar -public $className |
            Out-File -Encoding utf8 (Join-Path $outputDir "$safe.public.txt")
        if ($LASTEXITCODE -ne 0) { throw "javap failed: $className" }
        & $javap "-J-Djava.io.tmpdir=$outputDir" -classpath $jar -p -v $className |
            Out-File -Encoding utf8 (Join-Path $outputDir "$safe.verbose.txt")
        if ($LASTEXITCODE -ne 0) { throw "javap verbose failed: $className" }
    }
    Write-Output "$Name inspected SHA256=$digest"
}
$vehicleCache = Join-Path $workspace '.gradle-user-home\caches\modules-2\files-2.1\ai.pleos.playground\Vehicle\2.3.2'
$vehicle = @(Get-ChildItem -LiteralPath $vehicleCache -Filter 'Vehicle-2.3.2.aar' -Recurse)
if ($vehicle.Count -ne 1) { throw 'Expected one Vehicle 2.3.2 AAR.' }
Inspect-Aar $vehicle[0].FullName 'Vehicle-2.3.2' @('ai.pleos.playground.vehicle.api.SteeringWheel',
    'ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener', 'ai.pleos.playground.vehicle.constant.control.SteeringControl')
if (!$NaviAarPath) {
    $naviCache = Join-Path $workspace '.gradle-user-home\caches\modules-2\files-2.1\ai.pleos.playground\NaviHelper\3.0.5'
    if (Test-Path -LiteralPath $naviCache) { $NaviAarPath = (Get-ChildItem -LiteralPath $naviCache -Filter '*.aar' -Recurse | Select-Object -First 1).FullName }
}
if ($NaviAarPath) {
    Inspect-Aar $NaviAarPath 'NaviHelper-3.0.5' @('ai.pleos.playground.navi.helper.NaviHelper',
        'ai.pleos.playground.navi.helper.listener.NaviHelperEventListener')
    # Public data classes are in the compile dependency, not NaviHelper's classes.jar.
    $dataCache = Join-Path $workspace '.gradle-user-home\caches\modules-2\files-2.1\ai.pleos.playground\navi-data\3.0.4'
    $dataAar = @(Get-ChildItem -LiteralPath $dataCache -Recurse -Filter '*.aar')
    if ($dataAar.Count -ne 1) { throw 'Expected navi-data 3.0.4 AAR from NaviHelper POM.' }
    Inspect-Aar $dataAar[0].FullName 'navi-data-3.0.4' @('ai.pleos.playground.navi.data.TBTInfo',
        'ai.pleos.playground.navi.data.RouteStateInfo', 'ai.pleos.playground.navi.constants.TurnType',
        'ai.pleos.playground.navi.constants.NaviMode', 'ai.pleos.playground.navi.constants.NavigationStatus',
        'ai.pleos.playground.navi.constants.NaviErrorCode', 'ai.pleos.playground.navi.data.RouteInfo',
        'ai.pleos.playground.navi.constants.RouteOption')
    Write-Output 'Typed signatures verified. Double distance has no unit annotation; TBT passage callback is absent. See WEEK9_NAVI_FUSION.md.'
} else { Write-Output 'NaviHelper AAR unavailable: TBT signature/field semantics UNVERIFIED. Core build remains navigation-independent.' }
