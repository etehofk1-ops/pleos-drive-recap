param(
    [string]$ScenarioFile = 'scripts/recap-scenario-f-navi-demo.json',
    [string]$Serial = 'emulator-5554',
    [string]$AdbPath = '',
    [string]$ConsoleBaseUrl = 'http://127.0.0.1:8088',
    [switch]$DryRun,
    [switch]$StartImmediately
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Only emulator serials are supported' }
$origin = [Uri]$ConsoleBaseUrl
if ($origin.Scheme -ne 'http' -or $origin.Host -ne '127.0.0.1' -or $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/') { throw 'Console must be an HTTP 127.0.0.1 origin' }
$ConsoleBaseUrl = $ConsoleBaseUrl.TrimEnd('/')
$file = if ([IO.Path]::IsPathRooted($ScenarioFile)) { $ScenarioFile } else { Join-Path $workspace $ScenarioFile }
$scenario = Get-Content -Raw -Encoding UTF8 -LiteralPath $file | ConvertFrom-Json
if ($scenario.runner -ne 'demo-navi-feed.ps1' -or !$scenario.steps -or $scenario.steps.Count -gt 2000) { throw 'Expected bounded scenario F for demo-navi-feed.ps1' }
function Assert-Keys($Object, [string[]]$Allowed) {
    if ($null -eq $Object -or $Object -isnot [PSCustomObject]) { throw 'Expected JSON object' }
    foreach ($key in $Object.PSObject.Properties.Name) { if ($key -notin $Allowed) { throw "Unknown field: $key" } }
}
function Number($Value, [double]$Min, [double]$Max) {
    if ($null -eq $Value -or $Value -is [bool] -or $Value -isnot [ValueType]) { throw 'Expected JSON number' }
    $n = [double]$Value
    if ([double]::IsNaN($n) -or [double]::IsInfinity($n) -or $n -lt $Min -or $n -gt $Max) { throw 'Value out of range' }
    return $n
}
$held = @{ speedKph=0; steering=0; brake=0 }
$last = -1
$plan = @($scenario.steps | ForEach-Object {
    Assert-Keys $_ @('atMs','controls','navi','caption','waitForSpeedKph')
    $at = Number $_.atMs 0 300000
    if ($at -le $last -or $at -ne [Math]::Floor($at)) { throw 'Step times must be strictly increasing integer milliseconds' }
    $last = $at
    Assert-Keys $_.controls @('gear','speedKph','steering','brake','turnSignal')
    $controls = @{}; foreach ($key in $held.Keys) { $controls[$key]=$held[$key] }
    foreach ($p in $_.controls.PSObject.Properties) { $controls[$p.Name]=$p.Value }
    $speed = Number $controls.speedKph 0 240
    if ($speed -ne [Math]::Round($speed)) { throw 'Console requires integer speedKph' }
    [void](Number $controls.steering -470 500); [void](Number $controls.brake 0 100)
    if ($controls.ContainsKey('gear') -and $controls.gear -notin @('P','D')) { throw 'Scenario F allows P/D only' }
    if ($controls.ContainsKey('turnSignal') -and $controls.turnSignal -notin @('Off','Blink left','Blink right')) { throw 'Invalid turn signal' }
    $held = @{ speedKph=$speed; steering=$controls.steering; brake=$controls.brake }
    $navi = $_.navi
    if ($null -ne $navi) {
        Assert-Keys $navi @('guiding','action','distanceMeters')
        if ($navi.PSObject.Properties.Count -eq 0 -or $navi.guiding -isnot [bool]) { throw 'Invalid navi state' }
        if ($navi.guiding) {
            if ($navi.action -notin @('TURN_RIGHT','TURN_UTURN','TURN_STRAIGHT','TURN_UNKNOWN')) { throw 'Unsupported scenario maneuver' }
            if ($null -ne $navi.distanceMeters) { [void](Number $navi.distanceMeters 0 10000000) }
        } elseif ($null -ne $navi.action -or $null -ne $navi.distanceMeters) { throw 'Inactive navi must have null action/distance' }
    }
    $gate = $_.waitForSpeedKph
    if ($gate) {
        Assert-Keys $gate @('min','max','timeoutMs','holdMs')
        [void](Number $gate.min 0 240); [void](Number $gate.max $gate.min 240)
        [void](Number $gate.timeoutMs 200 60000); [void](Number $gate.holdMs 0 $gate.timeoutMs)
    }
    if ($_.caption -isnot [string] -or !$_.caption.Trim() -or $_.caption.Length -gt 120 -or $_.caption -match '[\r\n]') { throw 'Expected single-line caption' }
    [PSCustomObject]@{ At=[long]$at; Controls=$controls; Navi=$navi; Gate=$gate; Caption=$_.caption }
})
if ($plan[0].Controls.gear -ne 'P' -or $plan[0].Controls.speedKph -ne 0 -or !$plan[0].Navi -or $plan[-1].Controls.gear -ne 'P' -or $plan[-1].Controls.speedKph -ne 0) { throw 'F must begin/end parked and provide initial navi' }
if ($DryRun) { Write-Output "Validated F steps=$($plan.Count). No HTTP or adb calls. Receiver clock/3s expiry and labels require app tests."; exit 0 }
if (!$AdbPath) { $AdbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'adb missing' }
function Request([string]$Path, [string]$Method='GET') {
    Invoke-RestMethod -Uri ($ConsoleBaseUrl+$Path) -Method $Method -TimeoutSec 3
}
$catalog = Request '/vehicle/catalog'
$signal = @($catalog.features | Where-Object id -eq 'turn-signal-state')
if ($signal.Count -ne 1 -or $signal[0].write_endpoint -ne '/vehicle/turn-signal' -or $signal[0].control.field -ne 'mode') { throw 'Console turn-signal catalog contract changed' }
foreach ($mode in @('Off','BlinkLeft','BlinkRight')) { if ($mode -notin $signal[0].control.options.value) { throw 'Console turn-signal mode contract changed' } }
$initial = Request '/vehicle/state'
if ($initial.gear.actual.value -ne 'Park' -or $initial.controls.speed_kph -ne 0) { throw 'Start only in Park at speed 0' }
if (!$StartImmediately) {
    [void](Read-Host 'Debug 모의 토글 ON·기록 시작·초기 신호 준비됨을 확인하고 Console 브라우저 탭을 모두 닫으세요. Enter로 F 전체 재생')
}
$runId = [Guid]::NewGuid().ToString()
$script:sequence = 0L
$script:currentNavi = $null
$script:lastFeed = -1000L
$clock = [Diagnostics.Stopwatch]::StartNew()
function Send-Navi([switch]$Force) {
    if (!$script:currentNavi -or (!$Force -and $clock.ElapsedMilliseconds-$script:lastFeed -lt 1000)) { return }
    $json = @{ guiding=$script:currentNavi.guiding; action=$script:currentNavi.action; distanceMeters=$script:currentNavi.distanceMeters; runId=$runId; sequence=$script:sequence } | ConvertTo-Json -Compress
    $script:sequence++
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    # Base64 avoids Windows shell/adb JSON quote loss. No payload files, receiver in Debug only.
    $reply = @(& $AdbPath -s $Serial shell am broadcast --receiver-foreground -n lab.pleos.observe/.DemoNaviReceiver -a lab.pleos.observe.DEMO_NAVI --es payloadB64 $encoded 2>&1)
    if ($LASTEXITCODE -ne 0 -or !($reply -match 'demo_navi_accepted')) { throw 'Demo broadcast rejected. Check Debug toggle/active recording; no service is auto-started.' }
    $script:lastFeed = $clock.ElapsedMilliseconds
}
function Wait-Until([long]$Due) {
    while ($clock.ElapsedMilliseconds -lt $Due) { Send-Navi; $left=$Due-$clock.ElapsedMilliseconds; if($left -gt 0) { Start-Sleep -Milliseconds ([int][Math]::Min(100,$left)) } }
}
function Write-Controls($Controls) {
    if ($Controls.ContainsKey('gear')) { $g=if($Controls.gear -eq 'P'){'Park'}else{'Drive'}; [void](Request "/vehicle/gear?gear=$g" 'POST') }
    $culture=[Globalization.CultureInfo]::InvariantCulture
    $q='speed={0}&steering={1}&brake={2}' -f ([double]$Controls.speedKph).ToString('R',$culture),([double]$Controls.steering).ToString('R',$culture),([double]$Controls.brake).ToString('R',$culture)
    [void](Request ('/vehicle/controls?'+$q) 'POST')
    if ($Controls.ContainsKey('turnSignal')) {
        $m=@{Off='Off';'Blink left'='BlinkLeft';'Blink right'='BlinkRight'}[$Controls.turnSignal]
        [void](Request "/vehicle/turn-signal?mode=$m" 'POST')
    }
}
$ownsVehicle=$false
try {
    $shift=0L
    foreach ($step in $plan) {
        Wait-Until ($step.At+$shift)
        if ($clock.ElapsedMilliseconds-$step.At-$shift -gt 750) { throw 'Timeline drift >750ms; stop to avoid false steering evidence' }
        Write-Output ("F t={0}: {1}" -f $step.At,$step.Caption)
        if ($step.Navi) { $script:currentNavi=$step.Navi; Send-Navi -Force } else { Send-Navi }
        $ownsVehicle=$true
        Write-Controls $step.Controls
        # Preserve nominal inter-step gaps after IO, never catch up a slow adb/HTTP call in a burst.
        $shift=[Math]::Max($shift,$clock.ElapsedMilliseconds-$step.At)
        if ($step.Gate) {
            $began=$clock.ElapsedMilliseconds; $stable=0
            do {
                Send-Navi
                $state=Request '/vehicle/state'
                $v=Number $state.controls.speed_kph 0 240
                $stable=if($v -ge $step.Gate.min -and $v -le $step.Gate.max){$stable+1}else{0}
                if ($stable -ge 2 -and $clock.ElapsedMilliseconds-$began -ge $step.Gate.holdMs) { break }
                if ($clock.ElapsedMilliseconds-$began -gt $step.Gate.timeoutMs) { throw 'Speed gate timeout' }
                Wait-Until ($clock.ElapsedMilliseconds+200)
            } while ($true)
            $shift += $clock.ElapsedMilliseconds-$began
        }
    }
    Write-Output 'F completed. Expected: right good + U-turn missing + assisted lane missing + estimated lane missing. Actual app cards need AVD verification.'
} finally {
    if ($ownsVehicle) {
        try { Write-Controls @{ gear='P'; speedKph=0; steering=0; brake=0; turnSignal='Off' } }
        catch { Write-Warning 'Could not reset Console. Manually verify Park/0/neutral/Off.' }
    }
    # Keep last input only until its natural 3s expiry; never fabricate a final fresh guidance frame.
}
