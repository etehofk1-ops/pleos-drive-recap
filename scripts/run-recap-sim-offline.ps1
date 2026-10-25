param(
    [ValidateRange(1, 5000)][int]$Count = 200,
    [int]$Seed = 20261024,
    [switch]$Stress,
    [switch]$All,
    [ValidateSet('default','real')][string]$Noise = 'default',
    [switch]$Asym,
    [switch]$Week15Matrix,
    [string]$FrozenJar = '',
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Label = 'offline',
    [string]$RuntimeRoot = '.local/sim-runtime',
    [string]$AndroidStudioDir = 'C:\Program Files\Android\Android Studio',
    [string]$GradleHome = ''
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDir = [IO.Path]::GetFullPath((Join-Path $workspace $RuntimeRoot))
if (!$runtimeDir.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'RuntimeRoot must stay inside the workspace' }
$reports = Join-Path $workspace 'logic\build\reports\sim'
New-Item -ItemType Directory -Force $runtimeDir, $reports | Out-Null
$java = Join-Path $AndroidStudioDir 'jbr\bin\java.exe'
$kotlin = Join-Path $AndroidStudioDir 'plugins\Kotlin\kotlinc\lib'
if (!$GradleHome) {
    $cached = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.14.3-all'
    $bat = Get-ChildItem -LiteralPath $cached -Filter gradle.bat -Recurse | Select-Object -First 1
    if (!$bat) { throw 'Pass -GradleHome containing JUnit 4.13.2 and Hamcrest 1.3.' }
    $GradleHome = Split-Path (Split-Path $bat.FullName -Parent) -Parent
}
$jars = @((Join-Path $kotlin 'kotlin-stdlib.jar'), (Join-Path $GradleHome 'lib\junit-4.13.2.jar'),
    (Join-Path $GradleHome 'lib\hamcrest-core-1.3.jar'))
foreach ($path in @($java) + $jars) { if (!(Test-Path -LiteralPath $path)) { throw "Missing local tool: $path" } }
$cp = $jars -join [IO.Path]::PathSeparator
$source = @(Get-ChildItem (Join-Path $workspace 'logic\src\main\kotlin') -Recurse -Filter '*.kt' | ForEach-Object FullName)
$simSource = Join-Path $workspace 'logic\src\test\kotlin\lab\pleos\observe\logic\recap\RealisticDrivingSimTest.kt'
$jar = Join-Path $runtimeDir 'sim.jar'
$jvmArgs = @("-Djava.io.tmpdir=$runtimeDir", "-Duser.home=$runtimeDir")
if ($FrozenJar) { $jar = [IO.Path]::GetFullPath($FrozenJar) }
else {
    & $java @jvmArgs -cp (Join-Path $kotlin '*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
        -no-stdlib -no-reflect -jvm-target 17 -classpath $cp -d $jar @source $simSource
    if ($LASTEXITCODE -ne 0) { throw 'Simulation compilation failed.' }
}
$sets = @(@{Seed=$Seed; Stress=([int][bool]$Stress)})
if ($All) { $sets = @(@{Seed=20261024;Stress=0}, @{Seed=20261025;Stress=0}, @{Seed=20261026;Stress=0}, @{Seed=20261024;Stress=1}) }
if ($Week15Matrix) {
    $sets = @(foreach ($s in @(20261025,20261026)) { foreach ($n in @('real','default')) { foreach ($a in @(0,1)) {
        @{Seed=$s;Stress=0;Noise=$n;Asym=$a}
    } } })
}
$names = @('RECAP_SIM','RECAP_SIM_N','RECAP_SIM_SEED','RECAP_SIM_STRESS','RECAP_SIM_OUTPUT','RECAP_SIM_NOISE','RECAP_SIM_ASYM')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:RECAP_SIM='1'; $env:RECAP_SIM_N="$Count"
    foreach ($set in $sets) {
        $env:RECAP_SIM_SEED="$($set.Seed)"; $env:RECAP_SIM_STRESS="$($set.Stress)"
        $env:RECAP_SIM_NOISE = if ($set.ContainsKey('Noise')) { $set.Noise } else { $Noise }
        $env:RECAP_SIM_ASYM = if ($set.ContainsKey('Asym')) { "$($set.Asym)" } else { "$([int][bool]$Asym)" }
        $report = Join-Path $reports "$Label-$($set.Seed)-stress$($set.Stress)-$env:RECAP_SIM_NOISE-asym$env:RECAP_SIM_ASYM.md"
        $env:RECAP_SIM_OUTPUT=$report
        & $java -Xmx512m @jvmArgs -cp "$jar$([IO.Path]::PathSeparator)$cp" org.junit.runner.JUnitCore lab.pleos.observe.logic.recap.RealisticDrivingSimTest *> ($report + '.log')
        if ($LASTEXITCODE -ne 0) { throw "Simulation failed; see $report.log" }
        Write-Output "MEASURED seed=$($set.Seed) stress=$($set.Stress) noise=$env:RECAP_SIM_NOISE asym=$env:RECAP_SIM_ASYM n=$Count report=$report"
    }
} finally { foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') } }
