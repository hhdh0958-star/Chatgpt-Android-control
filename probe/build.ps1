param(
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$BuildTools = (Resolve-Path -LiteralPath $BuildTools).Path
$output = (New-Item -ItemType Directory -Force -Path $OutputDirectory).FullName

function Invoke-BuildTool($Executable, $ToolArguments) {
    & $Executable @ToolArguments
    if ($LASTEXITCODE) { throw "$Executable returned $LASTEXITCODE" }
}

$stage = (New-Item -ItemType Directory -Path (Join-Path $output ('build-' + [guid]::NewGuid()))).FullName
$classes = Join-Path $stage 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$resources = Join-Path $stage 'resources.zip'
$unsigned = Join-Path $stage 'unsigned.apk'
$aligned = Join-Path $stage 'aligned.apk'
$apk = Join-Path $output 'native-agent-probe.apk'
$key = Join-Path $output 'probe-debug.p12'
$manifest = Join-Path $stage 'AndroidManifest.xml'
$resourceSource = Join-Path $stage 'res'
New-Item -ItemType Directory -Force -Path (Join-Path $resourceSource 'xml') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'AndroidManifest.xml') -Destination $manifest
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'res/xml/chat_probe.xml') -Destination (Join-Path $resourceSource 'xml/chat_probe.xml')

$sources = @('ChatProbe.java', 'OperationText.java', 'TaskTrigger.java', 'ToolAudit.java', 'FileSession.java', 'AppIdentity.java', 'ProjectTools.java', 'ProjectGit.java', 'WriteReviewActivity.java', 'CalculatorInput.java', 'PhoneCalculator.java', 'PhoneScreen.java', 'PhoneCapture.java', 'PhoneCaptureProvider.java', 'CaptureDocuments.java', 'TimerInput.java', 'PhoneTimer.java', 'LogDiagnostics.java', '../ProjectFiles.java', '../Workspace.java') |
    ForEach-Object { Join-Path $PSScriptRoot $_ }
$sources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../projecttree') -Filter '*.java' -File |
    Select-Object -ExpandProperty FullName
$sources += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../runner/src') -Filter '*.java' -File |
    Select-Object -ExpandProperty FullName
$assets = Join-Path $stage 'assets'
New-Item -ItemType Directory -Force -Path $assets | Out-Null
Copy-Item -Recurse -Force -LiteralPath (Join-Path $PSScriptRoot '../runner/assets/python') -Destination $assets
$gitDeps = @(& (Join-Path $PSScriptRoot 'git-deps.ps1') -Directory (Join-Path $output 'git-deps'))
$jgitJar = $gitDeps | Where-Object { [IO.Path]::GetFileName($_).StartsWith('org.eclipse.jgit-') }
Copy-Item -Recurse -Force -LiteralPath (Join-Path $PSScriptRoot 'git-licenses') -Destination $assets
Invoke-BuildTool javac (@('--release', '17', '-encoding', 'UTF-8', '-cp', ((@($AndroidJar) + $gitDeps) -join ';'), '-d', $classes) + $sources)
$compiled = Join-Path $stage 'compiled.jar'
Invoke-BuildTool jar @('cf', $compiled, '-C', $classes, '.')
Invoke-BuildTool (Join-Path $BuildTools 'd8.bat') (@('--min-api', '34', '--lib', $AndroidJar, '--output', $stage, $compiled) + $gitDeps)
Invoke-BuildTool (Join-Path $BuildTools 'aapt2.exe') @('compile', '--dir', $resourceSource, '-o', $resources)
Invoke-BuildTool (Join-Path $BuildTools 'aapt2.exe') @('link', '-I', $AndroidJar, '--manifest', $manifest, '-o', $unsigned, $resources)
# JAR emits portable slash-separated entry names, including on Windows.
$messages = Join-Path $stage 'jgit-resources'
New-Item -ItemType Directory -Path $messages | Out-Null
Push-Location $messages
try { Invoke-BuildTool jar @('xf', $jgitJar, 'org/eclipse/jgit/internal/JGitText.properties') }
finally { Pop-Location }
Invoke-BuildTool jar @('uf', $unsigned, '-C', $stage, 'classes.dex', '-C', $stage, 'assets',
    '-C', $messages, 'org/eclipse/jgit/internal/JGitText.properties')
$entries = & jar tf $unsigned
foreach ($asset in (Get-ChildItem -LiteralPath (Join-Path $assets 'python') -File)) {
    if ("assets/python/$($asset.Name)" -cnotin $entries) { throw "Missing packaged asset: $($asset.Name)" }
}
Invoke-BuildTool (Join-Path $BuildTools 'zipalign.exe') @('-f', '4', $unsigned, $aligned)
if (!(Test-Path -LiteralPath $key)) {
    Invoke-BuildTool keytool @('-genkeypair', '-keystore', $key, '-storepass', 'android', '-alias', 'probe', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '365', '-dname', 'CN=Native Agent Local Debug')
}
Invoke-BuildTool (Join-Path $BuildTools 'apksigner.bat') @('sign', '--ks', $key, '--ks-pass', 'pass:android', '--out', $apk, $aligned)
Invoke-BuildTool (Join-Path $BuildTools 'apksigner.bat') @('verify', $apk)
Get-Item -LiteralPath $apk
