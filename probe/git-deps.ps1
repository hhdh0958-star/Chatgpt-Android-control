param([Parameter(Mandatory)][string]$Directory)
$ErrorActionPreference = 'Stop'
$deps = @{
    'org/eclipse/jgit/org.eclipse.jgit/7.8.1.202611041320-r/org.eclipse.jgit-7.8.1.202611041320-r' = 'acca09a77e74440fd24af72c8af4c496e42650d8092a4691a2935d9ce21d05d7'
    'com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3' = 'd65226949713c4c61a784f41c51167e7b0316f93764398ebba9e4336b3d954c2'
    'org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18' = '44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55'
    'commons-codec/commons-codec/1.22.1/commons-codec-1.22.1' = '78a5d732fbd715e2d10bd7150d2f8030bae57267f8aacc5c88f642cb6c2e5d3f'
}
New-Item -ItemType Directory -Force -Path $Directory | Out-Null
foreach ($coordinate in ($deps.Keys | Sort-Object)) {
    $name = ($coordinate -split '/')[-1] + '.jar'
    $file = Join-Path $Directory $name
    $expected = $deps[$coordinate]
    if (!(Test-Path -LiteralPath $file)) {
        $temp = "$file.$([guid]::NewGuid()).partial"
        Invoke-WebRequest "https://repo.maven.apache.org/maven2/$coordinate.jar" -OutFile $temp -TimeoutSec 30
        if ((Get-FileHash -LiteralPath $temp).Hash.ToLowerInvariant() -ne $expected) { throw "Checksum mismatch: $name" }
        Move-Item -LiteralPath $temp -Destination $file
    }
    if ((Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -ne $expected) { throw "Checksum mismatch: $name" }
    (Resolve-Path -LiteralPath $file).Path
}
