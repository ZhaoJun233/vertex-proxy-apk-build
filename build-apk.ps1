$ErrorActionPreference = 'Stop'

function Run-Checked([string]$Exe, [string[]]$CmdArgs) {
    & $Exe @CmdArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $Exe $($CmdArgs -join ' ')"
    }
}

$Build = Resolve-Path $PSScriptRoot
$Sdk = 'C:\Users\zhao\AppData\Local\CodexAndroidBuild\android-sdk'
$BuildTools = Join-Path $Sdk 'build-tools\35.0.0'
$AndroidJar = Join-Path $Sdk 'platforms\android-35\android.jar'
$Jdk = 'C:\Users\zhao\AppData\Local\CodexAndroidBuild\jdk17'

$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"

$Go = 'D:\Programming languages\go\bin\go.exe'
$env:Path = "$(Split-Path $Go);$env:Path"
$env:CGO_ENABLED = '0'
$env:GOOS = 'android'
$env:GOARCH = 'arm64'
Push-Location (Join-Path $Build 'src-go')
try {
    Run-Checked $Go ([string[]]@(
        'build',
        '-buildvcs=false',
        '-trimpath',
        '-ldflags=-s -w -X main.version=apk-android-dns-fix',
        '-o', (Join-Path $Build 'lib\arm64-v8a\libvproxy.so'),
        './cmd/vproxy'
    ))
} finally {
    Pop-Location
}

$Out = Join-Path $Build 'out'
$Classes = Join-Path $Out 'classes'
$Gen = Join-Path $Out 'gen'
$Dex = Join-Path $Out 'dex'
$ZipRoot = Join-Path $Out 'zip'

foreach ($Path in @($Out, $Classes, $Gen, $Dex, $ZipRoot)) {
    if ($Path -eq $Out) {
        if (!(Test-Path $Path)) {
            New-Item -ItemType Directory -Path $Path | Out-Null
        }
        continue
    }
    if (Test-Path $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path | Out-Null
}

Run-Checked (Join-Path $BuildTools 'aapt2.exe') ([string[]]@(
    'compile', '--dir', (Join-Path $Build 'res'),
    '-o', (Join-Path $Out 'resources.zip')
))

Run-Checked (Join-Path $BuildTools 'aapt2.exe') ([string[]]@(
    'link',
    '-o', (Join-Path $Out 'base.apk'),
    '-I', $AndroidJar,
    '--manifest', (Join-Path $Build 'AndroidManifest.xml'),
    '--java', $Gen,
    '--min-sdk-version', '23',
    '--target-sdk-version', '34',
    (Join-Path $Out 'resources.zip')
))

$Sources = @()
$Sources += Get-ChildItem -Path (Join-Path $Build 'src') -Recurse -Filter *.java | ForEach-Object {
    $_.FullName -replace '\\', '/'
}
$Sources += Get-ChildItem -Path $Gen -Recurse -Filter *.java | ForEach-Object {
    $_.FullName -replace '\\', '/'
}
$ArgFile = Join-Path $Out 'javac-sources.txt'
$Sources | Set-Content -Path $ArgFile -Encoding ASCII

Run-Checked (Join-Path $Jdk 'bin\javac.exe') ([string[]]@(
    '-encoding', 'UTF-8',
    '-source', '8',
    '-target', '8',
    '-classpath', ($AndroidJar -replace '\\', '/'),
    '-d', ($Classes -replace '\\', '/'),
    "@$($ArgFile -replace '\\', '/')"
))

Run-Checked (Join-Path $Jdk 'bin\jar.exe') ([string[]]@(
    'cf', (Join-Path $Out 'classes.jar'),
    '-C', $Classes, '.'
))

Run-Checked (Join-Path $BuildTools 'd8.bat') ([string[]]@(
    '--min-api', '23',
    '--lib', $AndroidJar,
    '--output', $Dex,
    (Join-Path $Out 'classes.jar')
))

Copy-Item -LiteralPath (Join-Path $Build 'assets') -Destination $ZipRoot -Recurse
New-Item -ItemType Directory -Path (Join-Path $ZipRoot 'lib\arm64-v8a') | Out-Null
Copy-Item -LiteralPath (Join-Path $Build 'lib\arm64-v8a\libvproxy.so') `
    -Destination (Join-Path $ZipRoot 'lib\arm64-v8a\libvproxy.so') -Force

$Unsigned = Join-Path $Out 'unsigned.apk'
$Aligned = Join-Path $Out 'aligned.apk'
$Final = Join-Path $Build 'vertex-proxy-local-proxy.apk'

if (Test-Path $Unsigned) {
    Remove-Item -LiteralPath $Unsigned -Force
}
Copy-Item -LiteralPath (Join-Path $Out 'base.apk') -Destination $Unsigned

Push-Location $ZipRoot
try {
    Run-Checked (Join-Path $Jdk 'bin\jar.exe') ([string[]]@('uf', $Unsigned, 'assets', 'lib'))
} finally {
    Pop-Location
}
Run-Checked (Join-Path $Jdk 'bin\jar.exe') ([string[]]@('uf', $Unsigned, '-C', $Dex, 'classes.dex'))

if (Test-Path $Aligned) {
    Remove-Item -LiteralPath $Aligned -Force
}
Run-Checked (Join-Path $BuildTools 'zipalign.exe') ([string[]]@('-f', '4', $Unsigned, $Aligned))

Run-Checked (Join-Path $BuildTools 'apksigner.bat') ([string[]]@(
    'sign',
    '--ks', (Join-Path $Build 'debug.keystore'),
    '--ks-key-alias', 'androiddebugkey',
    '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android',
    '--out', $Final,
    $Aligned
))

Run-Checked (Join-Path $BuildTools 'apksigner.bat') ([string[]]@('verify', '--verbose', $Final))
Run-Checked (Join-Path $BuildTools 'aapt2.exe') ([string[]]@('dump', 'badging', $Final))

tar -tf $Final | Select-String 'classes.dex|lib/arm64-v8a/libvproxy.so' | ForEach-Object {
    $_.Line
}

Get-Item $Final | Select-Object FullName, Length, LastWriteTime
