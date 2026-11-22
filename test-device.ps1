param(
    [Parameter(Mandatory)][string]$R8Jar,
    [string]$AndroidJar
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$R8Jar = (Resolve-Path -LiteralPath $R8Jar).Path
$build = Join-Path ([IO.Path]::GetTempPath()) ('native-agent-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $build | Out-Null

javac --release 17 -d $build (Join-Path $PSScriptRoot 'ProjectFiles.java') (Join-Path $PSScriptRoot 'Workspace.java') (Join-Path $PSScriptRoot 'WorkspaceTest.java')
if ($LASTEXITCODE) { throw 'Java compilation failed' }
java -cp $build dev.nativeagent.files.WorkspaceTest
if ($LASTEXITCODE) { throw 'Host test failed' }

$gitDeps = @()
if ($AndroidJar) {
    $AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
    $gitDeps = @(& (Join-Path $PSScriptRoot 'probe/git-deps.ps1') -Directory (Join-Path $env:TEMP 'native-agent-git-deps'))
    $protocolSources = @('FileSession.java','ProjectTools.java','ProjectGit.java','OperationText.java',
        'CalculatorInput.java','TimerInput.java','LogDiagnostics.java','PhoneScreen.java','FileSessionTest.java') |
        ForEach-Object { Join-Path $PSScriptRoot "probe/$_" }
    javac --release 17 -encoding UTF-8 -cp ((@($build,$AndroidJar) + $gitDeps) -join ';') -d $build $protocolSources
    if ($LASTEXITCODE) { throw 'Protocol test compilation failed' }
}
$classes = Get-ChildItem -LiteralPath $build -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName
java -cp $R8Jar com.android.tools.r8.D8 --min-api 34 --output $build (@($classes)+$gitDeps)
if ($LASTEXITCODE) { throw 'DEX compilation failed' }

$remote = (adb shell 'mktemp -d /data/local/tmp/native-dex.XXXXXX').Trim()
if ($LASTEXITCODE -or $remote -cnotmatch '^/data/local/tmp/native-dex\.[a-zA-Z0-9]+$') {
    throw 'Unable to create a test directory on the phone'
}
adb push (Join-Path $build 'classes.dex') "$remote/classes.dex"
if ($LASTEXITCODE) { throw 'DEX upload failed' }

$log = Join-Path $PSScriptRoot 'workspace-device-test.log'
adb shell "printf '%s\n' $remote && cd $remote && id && dalvikvm -Djava.io.tmpdir=$remote -cp $remote/classes.dex dev.nativeagent.files.WorkspaceTest" |
    Tee-Object -FilePath $log
if ($LASTEXITCODE) { throw 'Android test failed' }
if ($AndroidJar) {
    $protocolLog = Join-Path $PSScriptRoot 'protocol-device-test.log'
    $jgitJar = $gitDeps | Where-Object { [IO.Path]::GetFileName($_).StartsWith('org.eclipse.jgit-') }
    adb push $jgitJar "$remote/jgit.jar"
    adb shell "cd $remote && dalvikvm -Djava.io.tmpdir=$remote -cp $remote/classes.dex`:$remote/jgit.jar dev.nativeagent.probe.FileSessionTest" |
        Tee-Object -FilePath $protocolLog
    if ($LASTEXITCODE) { throw 'Android protocol test failed' }
}
