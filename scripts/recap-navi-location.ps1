param(
    [Parameter(Mandatory=$true)][ValidateSet('right-turn','uturn','lane')][string]$Phase,
    [string]$RouteFile = '.local/navi-e-route.json',
    [string]$Serial = 'emulator-5554',
    [string]$AdbPath = '',
    [int]$StartDelayMs = 0,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Emulator serial required; physical devices are not supported.' }
if ($StartDelayMs -lt 0 -or $StartDelayMs -gt 60000) { throw 'StartDelayMs must be 0..60000' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$file = if ([IO.Path]::IsPathRooted($RouteFile)) { $RouteFile } else { Join-Path $workspace $RouteFile }
$route = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
$property = $route.PSObject.Properties[$Phase]
if (!$property) { throw 'Phase missing from route file' }
$points = @($property.Value)
if ($points.Count -lt 2 -or $points.Count -gt 3600) { throw 'Each phase needs 2..3600 verified public test coordinates' }
function Number($Value, [double]$Min, [double]$Max) {
    if ($null -eq $Value -or $Value -is [bool] -or $Value -isnot [ValueType]) { throw 'Coordinate/time must be a JSON number; null template is not executable.' }
    $n = [double]$Value
    if ([double]::IsNaN($n) -or [double]::IsInfinity($n) -or $n -lt $Min -or $n -gt $Max) { throw 'Coordinate/time out of range' }
    return $n
}
$last = -1.0
$plan = @($points | ForEach-Object {
    $at = Number $_.atMs 0 300000
    if ($at -le $last -or $at -ne [Math]::Floor($at)) { throw 'atMs must be strictly increasing integer milliseconds' }
    $last = $at
    $lon = Number $_.longitude -180 180
    $lat = Number $_.latitude -90 90
    [PSCustomObject]@{ At = [long]$at; Longitude = $lon.ToString('R',[Globalization.CultureInfo]::InvariantCulture); Latitude = $lat.ToString('R',[Globalization.CultureInfo]::InvariantCulture) }
})
if ($DryRun) { Write-Output "Validated $($plan.Count) geo fixes for $Phase. No adb command executed. Route values not printed."; exit 0 }
if (!$AdbPath) { $AdbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'adb executable missing' }
Write-Output "Geo playback: $Phase; destination/road alignment and TBT reception must be verified separately."
$clock = [Diagnostics.Stopwatch]::StartNew()
foreach ($point in $plan) {
    $due = $StartDelayMs + $point.At
    while ($clock.ElapsedMilliseconds -lt $due) { Start-Sleep -Milliseconds ([int][Math]::Min(100, $due - $clock.ElapsedMilliseconds)) }
    # Android official syntax is LONGITUDE LATITUDE. Do not log coordinates or write them to app/AI.
    $reply = @(& $AdbPath -s $Serial emu geo fix $point.Longitude $point.Latitude 2>&1)
    if ($LASTEXITCODE -ne 0 -or $reply -match '(^|\s)(KO|error)(:|\s|$)') { throw 'geo fix failed; playback stopped' }
    Write-Output "geo point sent t=$($point.At)"
}
Write-Output 'Playback finished. Emulator acknowledgment is not proof NaviHelper consumed the fixes.'
