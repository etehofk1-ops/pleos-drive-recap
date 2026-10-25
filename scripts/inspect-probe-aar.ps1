param([string]$AndroidStudioDir = 'C:\Program Files\Android\Android Studio')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDir = Join-Path $workspace '.local\w3-aar'
$cacheDir = Join-Path $workspace '.gradle-user-home\caches\modules-2\files-2.1\ai.pleos.playground\Vehicle\2.3.2'
$aarFiles = @(Get-ChildItem -LiteralPath $cacheDir -Recurse -Filter 'Vehicle-2.3.2.aar')
if ($aarFiles.Count -ne 1) { throw 'Expected exactly one cached Vehicle-2.3.2.aar.' }
$aar = $aarFiles[0].FullName
$javap = Join-Path $AndroidStudioDir 'jbr\bin\javap.exe'
if (!(Test-Path -LiteralPath $javap)) { throw 'Android Studio javap.exe not found.' }
New-Item -ItemType Directory -Force $outputDir | Out-Null
Get-FileHash -LiteralPath $aar -Algorithm SHA256 |
    Format-List | Out-File -Encoding utf8 (Join-Path $outputDir 'AAR-SHA256.txt')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = Join-Path $outputDir 'Vehicle-2.3.2-classes.jar'
$zip = [IO.Compression.ZipFile]::OpenRead($aar)
try {
    $entry = $zip.GetEntry('classes.jar')
    if (!$entry) { throw 'classes.jar is missing from AAR.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $jar, $true)
} finally { $zip.Dispose() }

$rootPackage = 'ai.pleos.playground.vehicle'
$classes = @(
    'Vehicle', 'api.Odometer', 'api.EvBattery', 'api.Light', 'api.Door', 'api.Seat', 'api.Wiper',
    'api.model.capability.CapabilityConfig', 'api.model.capability.AccessType', 'api.model.capability.AreaBounds',
    'api.model.area.SeatArea', 'api.model.area.DoorArea', 'api.model.area.WindowArea',
    'constant.area.SeatValue', 'constant.area.DoorValue', 'constant.area.WindowValue',
    'constant.control.LightState', 'constant.control.WiperSwitch', 'constant.control.WiperState',
    'listener.VehicleListener', 'listener.DistanceDrivenListener', 'listener.RangeRemainingListener',
    'listener.EvBatteryStateOfChargeListener', 'listener.AmbientLightBrightnessColorListener',
    'listener.AmbientLightBrightnessListener', 'listener.LightStateListener',
    'listener.DoorPositionListener', 'listener.SeatOccupancyListener', 'listener.WiperStateListener'
)
foreach ($className in $classes) {
    $safeName = $className.Replace('.', '_')
    & $javap "-J-Djava.io.tmpdir=$outputDir" "-J-Duser.home=$(Join-Path $workspace '.local')" `
        -classpath $jar -public "$rootPackage.$className" |
        Out-File -Encoding utf8 (Join-Path $outputDir "$safeName.public.txt")
    if ($LASTEXITCODE -ne 0) { throw "javap failed: $className" }
    if ($className.StartsWith('api.') -or $className.StartsWith('listener.')) {
        # -v preserves RequiresPermission, Nullable and Kotlin metadata evidence.
        & $javap "-J-Djava.io.tmpdir=$outputDir" "-J-Duser.home=$(Join-Path $workspace '.local')" `
            -classpath $jar -p -v "$rootPackage.$className" |
            Out-File -Encoding utf8 (Join-Path $outputDir "$safeName.verbose.txt")
        if ($LASTEXITCODE -ne 0) { throw "javap verbose failed: $className" }
    }
}
Write-Output "AAR inspection complete: $outputDir"
