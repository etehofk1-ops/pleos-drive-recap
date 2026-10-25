param(
    [string]$AndroidStudioDir = 'C:\Program Files\Android\Android Studio',
    [string]$GradleHome = '',
    [string]$OutputRoot = '.local/offline-tests',
    [string]$TestClass = ''
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDir = [IO.Path]::GetFullPath((Join-Path $workspace $OutputRoot))
if (!$outputDir.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'OutputRoot must stay inside the workspace' }
$tempDir = Join-Path $outputDir 'tmp'
New-Item -ItemType Directory -Force $outputDir, $tempDir | Out-Null
$javaExe = Join-Path $AndroidStudioDir 'jbr\bin\java.exe'
$kotlinHome = Join-Path $AndroidStudioDir 'plugins\Kotlin\kotlinc'
$stdlib = Join-Path $kotlinHome 'lib\kotlin-stdlib.jar'
if (!$GradleHome) {
    # Read the preinstalled distribution. Never populate or mutate the user's cache.
    $cachedDist = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.14.3-all'
    $gradleBat = Get-ChildItem -LiteralPath $cachedDist -Filter gradle.bat -Recurse | Select-Object -First 1
    if (!$gradleBat) { throw 'Pass -GradleHome for a distribution containing JUnit 4.13.2 and Hamcrest 1.3.' }
    $GradleHome = Split-Path (Split-Path $gradleBat.FullName -Parent) -Parent
}
$junit = Join-Path $GradleHome 'lib\junit-4.13.2.jar'
$hamcrest = Join-Path $GradleHome 'lib\hamcrest-core-1.3.jar'
foreach ($path in @($javaExe, $stdlib, $junit, $hamcrest)) {
    if (!(Test-Path -LiteralPath $path)) { throw "Required local tool missing: $path" }
}
$classPath = @($stdlib, $junit, $hamcrest) -join [IO.Path]::PathSeparator
$mainSources = @(Get-ChildItem (Join-Path $workspace 'logic\src\main\kotlin') -Recurse -Filter '*.kt' | ForEach-Object FullName)
$testSources = @(Get-ChildItem (Join-Path $workspace 'logic\src\test\kotlin') -Recurse -Filter '*.kt' | ForEach-Object FullName)
$testClasses = @($testSources | ForEach-Object {
    $sourceText = Get-Content -LiteralPath $_ -Raw -Encoding UTF8
    $packageName = [regex]::Match($sourceText, '(?m)^package\s+([\w.]+)').Groups[1].Value
    [regex]::Matches($sourceText, '(?m)^class\s+(\w+Test)\b') | ForEach-Object { "$packageName.$($_.Groups[1].Value)" }
})
if (!$testClasses.Count) { throw 'No JUnit test classes discovered.' }
if ($TestClass) { $testClasses = @($TestClass.Split(',')) }
$testJar = Join-Path $outputDir 'logic-tests.jar'
$jvmArgs = @("-Djava.io.tmpdir=$tempDir", "-Duser.home=$outputDir")
& $javaExe @jvmArgs -cp (Join-Path $kotlinHome 'lib\*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $classPath -d $testJar @mainSources @testSources
if ($LASTEXITCODE -ne 0) { throw "Kotlin compile failed: $LASTEXITCODE" }
$resources = Join-Path $workspace 'logic\src\test\resources'
& $javaExe @jvmArgs -cp "$testJar$([IO.Path]::PathSeparator)$classPath$([IO.Path]::PathSeparator)$resources" `
    org.junit.runner.JUnitCore @testClasses 2>&1 |
    Tee-Object -FilePath (Join-Path $outputDir 'junit-result.txt')
if ($LASTEXITCODE -ne 0) { throw "JUnit failed: $LASTEXITCODE" }
