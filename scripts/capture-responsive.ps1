param(
    [ValidateSet('Inspect','Resize','Capture','Day','Night','System','Restore')][string]$Action = 'Inspect',
    [ValidatePattern('^emulator-[0-9]+$')][string]$Serial = 'emulator-5554',
    [string]$Size = '',
    [ValidatePattern('^[a-zA-Z0-9_-]{1,48}$')][string]$Label = 'responsive',
    [string]$AdbPath = 'adb',
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDir = Join-Path $workspace '.local\responsive'
$baselinePath = Join-Path $outputDir ($Serial + '-baseline.json')
if ($Action -eq 'Resize' -and ($Size -notmatch '^([0-9]{3,4})x([0-9]{3,4})$' -or
    [int]$Matches[1] -lt 320 -or [int]$Matches[2] -lt 320 -or [int]$Matches[1] -gt 7680 -or [int]$Matches[2] -gt 7680)) {
    throw 'Resize requires an integer pixel size in 320..7680, e.g. -Size 840x1212. Pixels do not imply window dp.'
}
# Default is a plan: no ADB, directories, screenshots or device changes without -Apply.
$command = switch ($Action) {
    'Inspect' { 'shell wm size; shell wm density; shell am get-config; shell cmd uimode night' }
    'Resize' { "shell wm size $Size" }
    'Capture' { "exec-out screencap -p -> .local/responsive/$Label.png (binary stream)" }
    'Day' { 'shell cmd uimode night no' }
    'Night' { 'shell cmd uimode night yes' }
    'System' { 'shell cmd uimode night auto' }
    'Restore' { 'restore original wm size override and night mode from local baseline (density is never changed)' }
}
if (!$Apply) { Write-Output "PLAN ONLY: adb -s $Serial $command"; return }
$adbCommand = Get-Command $AdbPath -ErrorAction Stop
$adb = $adbCommand.Source
function AdbText([string[]]$Parts) {
    $result = & $adb -s $Serial @Parts 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed for $($Parts -join ' ')" }
    return ($result -join "`n")
}
if ((AdbText @('get-state')).Trim() -ne 'device') { throw 'Selected emulator is not ready.' }
New-Item -ItemType Directory -Force $outputDir | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
if ($Action -in @('Resize','Day','Night','System') -and !(Test-Path -LiteralPath $baselinePath)) {
    $sizeBefore = AdbText @('shell','wm','size')
    $nightBefore = AdbText @('shell','cmd','uimode','night')
    $override = if ($sizeBefore -match 'Override size:\s*([0-9]+x[0-9]+)') { $Matches[1] } else { 'reset' }
    if ($nightBefore -notmatch 'Night mode:\s*(auto|no|yes|custom)\b') { throw 'Cannot capture original night mode; no mutation performed.' }
    $night = $Matches[1]
    if ($night -eq 'custom') { throw 'Custom night mode detected; use manual commands and preserve its custom schedule.' }
    [IO.File]::WriteAllText($baselinePath, (@{ size = $override; night = $night } | ConvertTo-Json), $utf8)
}
switch ($Action) {
    'Resize' { AdbText @('shell','wm','size',$Size) | Write-Output }
    'Day' { AdbText @('shell','cmd','uimode','night','no') | Write-Output }
    'Night' { AdbText @('shell','cmd','uimode','night','yes') | Write-Output }
    'System' { AdbText @('shell','cmd','uimode','night','auto') | Write-Output }
    'Restore' {
        if (!(Test-Path -LiteralPath $baselinePath)) { throw 'No baseline for this emulator; original settings are unknown.' }
        $baseline = Get-Content -LiteralPath $baselinePath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($baseline.size -notmatch '^(reset|[0-9]{3,4}x[0-9]{3,4})$' -or $baseline.night -notin @('auto','no','yes')) { throw 'Invalid local baseline.' }
        AdbText @('shell','wm','size',[string]$baseline.size) | Write-Output
        AdbText @('shell','cmd','uimode','night',[string]$baseline.night) | Write-Output
        # Keep the baseline as evidence; do not silently replace the known original.
    }
    'Capture' {
        # PowerShell 5 redirection corrupts PNG bytes. Copy stdout directly, no device file.
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = $adb; $info.Arguments = "-s $Serial exec-out screencap -p"
        $info.UseShellExecute = $false; $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
        $process = New-Object Diagnostics.Process
        $process.StartInfo = $info
        $target = Join-Path $outputDir ($Label + '.png')
        if ((Test-Path -LiteralPath $target) -or (Test-Path -LiteralPath (Join-Path $outputDir ($Label + '.txt')))) {
            throw 'Capture label already exists. Choose a new label to preserve evidence.'
        }
        $file = [IO.File]::Create($target)
        try {
            [void]$process.Start()
            $errors = $process.StandardError.ReadToEndAsync()
            $process.StandardOutput.BaseStream.CopyTo($file)
            $process.WaitForExit()
            if ($process.ExitCode -ne 0) { throw 'PNG capture failed; output is not valid evidence.' }
        } finally { $file.Dispose(); $process.Dispose() }
        $bytes = [IO.File]::ReadAllBytes($target)
        if ($bytes.Length -lt 8 -or [BitConverter]::ToString($bytes[0..7]) -ne '89-50-4E-47-0D-0A-1A-0A') { throw 'PNG signature mismatch.' }
        Write-Output "Saved default display capture: $target. DISPLAY2 requires its own host-window capture."
    }
}
$metadata = @(('size: ' + (AdbText @('shell','wm','size'))), ('density: ' + (AdbText @('shell','wm','density'))),
    ('config: ' + (AdbText @('shell','am','get-config'))), ('night: ' + (AdbText @('shell','cmd','uimode','night'))))
$metadata | Write-Output
if ($Action -eq 'Capture') { [IO.File]::WriteAllText((Join-Path $outputDir ($Label + '.txt')), ($metadata -join "`n"), $utf8) }
Write-Output 'No Activity relaunch/force-stop or recorder command was issued. Verify UI state and service PID separately.'
