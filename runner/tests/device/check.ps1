param(
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$TargetBuild,
    [ValidateSet('python','scripts','storage')][string]$Mode = 'python',
    [switch]$RunInstalled
)
$ErrorActionPreference = 'Stop'
function Run($program, $arguments) {
    & $program @arguments
    if ($LASTEXITCODE) { throw "$program failed with $LASTEXITCODE" }
}
if (!$RunInstalled) {
    $out = (New-Item -ItemType Directory -Path (Join-Path $env:TEMP ('native-agent-instrument-' + [guid]::NewGuid()))).FullName
    $manifest = Join-Path $out 'AndroidManifest.xml'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'AndroidManifest.xml') -Destination $manifest
    $sources = Join-Path $PSScriptRoot '../../src'
    $root = Join-Path $PSScriptRoot '../../..'
    # Compile against the target's class signatures; only package the instrumentation class.
    Run javac @('--release','17','-cp',$AndroidJar,'-sourcepath',$sources,'-d',$out,
        (Join-Path $sources 'PythonRunner.java'),(Join-Path $sources 'PythonService.java'),(Join-Path $PSScriptRoot 'RunnerCheck.java'),
        (Join-Path $root 'ProjectFiles.java'),(Join-Path $root 'Workspace.java'),(Join-Path $root 'projecttree/TreeFileStore.java'),(Join-Path $root 'projecttree/VolumeGuard.java'))
    Run (Join-Path $BuildTools 'd8.bat') @('--min-api','34','--lib',$AndroidJar,'--classpath',$out,'--output',$out,
        (Join-Path $out 'dev/nativeagent/tests/RunnerCheck.class'))
    $unsigned = Join-Path $out 'unsigned.apk'
    $aligned = Join-Path $out 'aligned.apk'
    $apk = Join-Path $out 'checks.apk'
    Run (Join-Path $BuildTools 'aapt2.exe') @('link','-I',$AndroidJar,'--manifest',$manifest,'-o',$unsigned)
    Run jar @('uf',$unsigned,'-C',$out,'classes.dex')
    Run (Join-Path $BuildTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
    Run (Join-Path $BuildTools 'apksigner.bat') @('sign','--ks',(Join-Path $TargetBuild 'probe-debug.p12'),'--ks-pass','pass:android','--out',$apk,$aligned)
    Write-Output "Install this test APK with MT before running this script with -RunInstalled: $apk"
    return
}
$log = & adb shell am instrument -w -e mode $Mode dev.nativeagent.tests/.RunnerCheck
$exitCode = $LASTEXITCODE
$log
$expected = @{
    python = 'PASS both phone-local unittest exit codes'
    scripts = 'PASS Shell and JavaScript on Android'
    storage = 'PASS authorized volume, new file, overwrite, backup and stale write'
}[$Mode]
if ($exitCode -or ($log -join "`n") -notmatch [regex]::Escape($expected)) {
    throw "Device instrumentation failed for $Mode"
}
