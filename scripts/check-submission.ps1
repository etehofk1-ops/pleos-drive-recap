param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$SourceOnly,
    [string]$NameListFile = '',
    [string]$TeamName = '',
    [string]$Aapt2Path = '',
    [string]$ApkSignerPath = ''
)
$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
$failures = New-Object 'System.Collections.Generic.List[string]'
function Fail([string]$message) { $failures.Add($message); Write-Output ('[FAIL] ' + $message) }
function Invoke-Captured([string]$command, [string[]]$toolArguments) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $command @toolArguments 2>&1 | ForEach-Object { $_.ToString() })
        return [PSCustomObject]@{ Code=$LASTEXITCODE; Lines=$lines }
    } catch { return [PSCustomObject]@{ Code=-1; Lines=@() } }
    finally { $ErrorActionPreference = $savedPreference }
}

# These exclusions must also be applied when preparing the submission repository.
# Ignoring a tracked file does not remove it from Git or its history.
function Excluded([string]$relative) {
    $path = $relative.Replace('\', '/')
    if ($path -match '(^|/)(\.git|\.local|\.gradle|\.gradle-user-home|\.kotlin|\.idea|\.omc|\.agents|\.codex|build|node_modules)(/|$)') { return $true }
    if ($path -match '^docs/(dev-notes|evidence)(/|$)') { return $true }
    if ($path -match '^motion(/|$)') { return $true }
    if ($path -match '^docs/(WEEK[^/]*\.md|WORK-CHECKLIST\.md|API_EVIDENCE\.md)$') { return $true }
    if ($path -match '^(_codex_log[^/]*|_codex_report[^/]*|SPEC_[^/]*|local\.properties)$') { return $true }
    return $false
}

$rules = [ordered]@{
    'email' = '(?i)(?<![\w.+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}(?![\w.-])'
    'phone' = '(?<![A-Za-z0-9])(?:\+82[- .]?(?:0)?1[016789]|01[016789])[- .]?\d{3,4}[- .]?\d{4}(?![A-Za-z0-9])|(?<![A-Za-z0-9])0[2-6]\d?[- ]\d{3,4}[- ]\d{4}(?![A-Za-z0-9])'
    'bearer-value' = '(?i)\bBearer[ \t]+[A-Za-z0-9._~+/=-]{12,}'
    'key-prefix' = '(?i)\bsk[-_][A-Za-z0-9_-]{16,}'
    'key-assignment' = '(?i)\b(?:TYPESAFE_API_KEY|OPENCODE_GO_API_KEY|OPENAI_API_KEY)\s*[:=]\s*["''][A-Za-z0-9_-]{12,}'
    'private-key' = '-----BEGIN ' + '(?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    'personal-home-path' = '(?i)[A-Z]:\\Users\\[^\\\s"''<>]+|/(?:Users|home)/[^/\s"''<>]+'
    'labelled-name' = '(?:^|[\s{])(?:성명|실명|대표자명)\s*[:=]\s*["'']?[가-힣]{2,4}'
}
if ($NameListFile) {
    $names = @(Get-Content -LiteralPath $NameListFile -Encoding UTF8 | Where-Object { $_.Trim() -and !$_.StartsWith('#') })
    if (!$names.Count) { Fail 'name-list-empty' }
    else { $rules['provided-name'] = ($names | ForEach-Object { [Regex]::Escape($_.Trim()) }) -join '|' }
} else { Write-Output '[NOTE] Unlabelled real names need manual review or -NameListFile (local, one name per line).' }

function Check-Lines([string]$label, [string[]]$lines) {
    for ($i = 0; $i -lt $lines.Count; $i++) {
        foreach ($category in $rules.Keys) {
            if ([Regex]::IsMatch($lines[$i], $rules[$category])) {
                # Report only the location/category, NEVER matching text or a credential.
                Fail ($label + ':' + ($i + 1) + ' category=' + $category)
            }
        }
    }
}

$readme = Join-Path $rootPath 'README.md'
if (!(Test-Path -LiteralPath $readme -PathType Leaf)) { Fail 'README.md missing' }
else {
    $text = Get-Content -LiteralPath $readme -Raw -Encoding UTF8
    foreach ($heading in @('앱 이름 및 한 줄 소개', '실행 방법', '테스트 환경 및 필요한 설정', 'APK 위치')) {
        if ($text -notmatch ('(?m)^##\s+' + [Regex]::Escape($heading) + '\s*$')) { Fail ('README section missing: ' + $heading) }
    }
    if ($text -notmatch '오운복' -or $text -notmatch 'apk/') { Fail 'README app name/APK path missing' }
}

# Walk without following junctions/symlinks; never read local signing material or excluded notes.
$pending = New-Object 'System.Collections.Generic.Stack[string]'
$pending.Push($rootPath)
$checkedFiles = 0
while ($pending.Count) {
    $directory = $pending.Pop()
    foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
        $relative = $entry.FullName.Substring($rootPath.Length + 1).Replace('\', '/')
        if (Excluded $relative) { continue }
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { Fail ($relative + ' reparse-point requires review'); continue }
        if ($entry.PSIsContainer) { $pending.Push($entry.FullName); continue }
        if ($entry.Name -match '(?i)\.(keystore|jks|p12|pfx)$|^(\.env(?:\..*)?|release\.properties|credentials\.json)$') {
            Fail ($relative + ' local-secret-file'); continue
        }
        if ($entry.Extension -in @('.apk', '.jar', '.png', '.jpg', '.jpeg', '.mp4', '.webm', '.bin', '.zip')) { continue }
        if ($entry.Length -gt 2MB) { Fail ($relative + ' oversized-text requires review'); continue }
        Check-Lines $relative @(Get-Content -LiteralPath $entry.FullName -Encoding UTF8)
        $checkedFiles++
    }
}

$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $top = Invoke-Captured $git.Source @('-C', $rootPath, 'rev-parse', '--show-toplevel')
    if ($top.Code -eq 0 -and [IO.Path]::GetFullPath(($top.Lines | Select-Object -First 1)).TrimEnd('\', '/') -eq $rootPath) {
        # Commit messages only; author identities and historical file blobs need a separate pre-publication review.
        $messages = Invoke-Captured $git.Source @('-C', $rootPath, 'log', '--all', '--format=%h%n%B%n')
        if ($messages.Code -ne 0) { Fail 'Git commit message read failed' } else { Check-Lines 'git-message' $messages.Lines }
        $tracked = Invoke-Captured $git.Source @('-C', $rootPath, 'ls-files')
        if ($tracked.Code -ne 0) { Fail 'Git tracked file read failed' }
        $trackedExcluded = @($tracked.Lines | Where-Object { Excluded $_ })
        if ($trackedExcluded.Count) {
            Write-Output ('[NOTE] Existing Git tracks ' + $trackedExcluded.Count + ' excluded files. Export without these paths/history before submission.')
        }
    } elseif (Test-Path -LiteralPath (Join-Path $rootPath '.git')) { Fail 'Git history could not be inspected' }
    else { Write-Output '[NOTE] This root has no own Git history; ancestor repository history was not included.' }
} elseif (Test-Path -LiteralPath (Join-Path $rootPath '.git')) {
    Fail 'Git is required to inspect existing commit messages'
} else {
    Write-Output '[NOTE] Git unavailable; this source root has no Git history.'
}

$apkDir = Join-Path $rootPath 'apk'
if (!(Test-Path -LiteralPath $apkDir -PathType Container)) { Fail 'apk/ directory missing' }
elseif (!(Test-Path -LiteralPath (Join-Path $apkDir '.gitkeep') -PathType Leaf)) { Fail 'apk/.gitkeep missing' }
if (!$SourceOnly -and (Test-Path -LiteralPath $apkDir -PathType Container)) {
    $apks = @(Get-ChildItem -LiteralPath $apkDir -File -Filter '*.apk')
    if ($apks.Count -ne 1) { Fail 'Exactly one signed release APK is required in apk/' }
    else {
        $apk = $apks[0]
        $nameValid = $apk.Name -match '^.+_오운복_release\.apk$' -and $apk.Name -notmatch '^팀명_'
        if ($TeamName) { $nameValid = $nameValid -and $apk.Name -eq ($TeamName + '_오운복_release.apk') }
        if (!$nameValid) { Fail 'APK filename must be actual-team_오운복_release.apk' }
        if ($apk.Length -eq 0 -or $apk.Length -gt 100MB) { Fail 'APK size is empty or exceeds repository upload limit' }
        if (!$Aapt2Path -or !$ApkSignerPath) {
            $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
            $tools = Join-Path $sdk 'build-tools'
            $toolDirectory = if (Test-Path -LiteralPath $tools) { Get-ChildItem -LiteralPath $tools -Directory | Sort-Object Name -Descending | Select-Object -First 1 }
            if ($toolDirectory) {
                if (!$Aapt2Path) { $Aapt2Path = Join-Path $toolDirectory.FullName 'aapt2.exe' }
                if (!$ApkSignerPath) { $ApkSignerPath = Join-Path $toolDirectory.FullName 'apksigner.bat' }
            }
        }
        if (!$Aapt2Path -or !(Test-Path -LiteralPath $Aapt2Path -PathType Leaf)) { Fail 'aapt2 required for release APK verification' }
        else {
            $badgingResult = Invoke-Captured $Aapt2Path @('dump', 'badging', $apk.FullName)
            $badging = $badgingResult.Lines
            if ($badgingResult.Code -ne 0) { Fail 'APK badging failed' }
            else {
                $package = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
                if (!$package -or $package -notmatch "name='lab.pleos.observe'" -or $package -notmatch "versionCode='27'" -or $package -notmatch "versionName='1.9.3'") { Fail 'APK package/version mismatch' }
                if ($badging -match '^application-debuggable') { Fail 'Debuggable APK is not a release submission' }
            }
        }
        if (!$ApkSignerPath -or !(Test-Path -LiteralPath $ApkSignerPath -PathType Leaf)) { Fail 'apksigner required for signature verification' }
        else {
            $certificateResult = Invoke-Captured $ApkSignerPath @('verify', '--verbose', '--print-certs', $apk.FullName)
            $certificate = $certificateResult.Lines
            if ($certificateResult.Code -ne 0) { Fail 'APK signature verification failed' }
            elseif (!($certificate -match '^Signer #\d+ certificate DN:')) { Fail 'APK signer certificate was not reported' }
            elseif ($certificate -match 'CN=Android Debug') { Fail 'Debug certificate is not a release submission' }
            else { Check-Lines 'apk-certificate' @($certificate | ForEach-Object { $_.ToString() }) }
        }
    }
}

Write-Output ('Checked text files=' + $checkedFiles + ' failures=' + $failures.Count)
Write-Output '[NOTE] Review app screens/media, unlabelled names, Git authors and historical blobs manually. No history is rewritten.'
if ($failures.Count) { exit 1 }
if ($SourceOnly) { Write-Output '[PASS] Source scope only. APK/build/runtime verification remains.' }
else { Write-Output '[PASS] Submission source/signature/version checks. Actual release AVD run still requires confirmation.' }
exit 0
