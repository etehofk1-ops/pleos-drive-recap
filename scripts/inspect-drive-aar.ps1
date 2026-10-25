param([string]$AndroidStudioDir = 'C:\Program Files\Android\Android Studio')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDir = Join-Path $workspace '.local\w4-aar'
$cacheDir = Join-Path $workspace '.gradle-user-home\caches\modules-2\files-2.1\ai.pleos.playground\Vehicle\2.3.2'
$files = @(Get-ChildItem -LiteralPath $cacheDir -Recurse -Filter 'Vehicle-2.3.2.aar')
if ($files.Count -ne 1) { throw 'Expected one cached Vehicle-2.3.2.aar.' }
$javap = Join-Path $AndroidStudioDir 'jbr\bin\javap.exe'
New-Item -ItemType Directory -Force $outputDir | Out-Null
$hash = (Get-FileHash -LiteralPath $files[0].FullName -Algorithm SHA256).Hash
"SHA256=$hash" | Out-File -Encoding utf8 (Join-Path $outputDir 'AAR-SHA256.txt')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = Join-Path $outputDir 'Vehicle-2.3.2-classes.jar'
$zip = [IO.Compression.ZipFile]::OpenRead($files[0].FullName)
try {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'), $jar, $true)
} finally { $zip.Dispose() }
$classes = @('Vehicle', 'api.TurnSignal', 'api.SteeringWheel', 'api.Brake',
    'listener.TurnSignalStateListener', 'listener.SteeringWheelAngleListener', 'listener.BrakePedalStatusListener',
    'listener.VehicleListener', 'constant.area.TurnSignalValue')
foreach ($className in $classes) {
    $name = $className.Replace('.', '_')
    & $javap "-J-Djava.io.tmpdir=$outputDir" -classpath $jar -public "ai.pleos.playground.vehicle.$className" |
        Out-File -Encoding utf8 (Join-Path $outputDir "$name.public.txt")
    if ($LASTEXITCODE -ne 0) { throw "javap failed: $className" }
    & $javap "-J-Djava.io.tmpdir=$outputDir" -classpath $jar -p -v "ai.pleos.playground.vehicle.$className" |
        Out-File -Encoding utf8 (Join-Path $outputDir "$name.verbose.txt")
    if ($LASTEXITCODE -ne 0) { throw "javap verbose failed: $className" }
}
Write-Output "AAR inspection complete: $outputDir SHA256=$hash"
