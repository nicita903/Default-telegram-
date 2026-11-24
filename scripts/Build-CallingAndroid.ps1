param(
    [Parameter(Mandatory = $true)][string]$MsysRoot,
    [string]$NdkRoot = '',
    [ValidateRange(1,16)][int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root '.native'
$pins = Get-Content -Raw -LiteralPath (Join-Path $root 'native/calling/sources.json') | ConvertFrom-Json
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Calling native command failed: $exe" }
}
$source = Join-Path $native 'telegram-x-calls'
if (!(Test-Path -LiteralPath $source)) { Run 'git' @('clone','--depth','1','--filter=blob:none','--sparse',$pins.telegramX.url,$source) }
Run 'git' @('-C',$source,'fetch','--depth','1','origin',$pins.telegramX.commit)
Run 'git' @('-C',$source,'sparse-checkout','set','app/jni','thirdparty/WebRTC/src/java')
Run 'git' @('-C',$source,'checkout','--detach',$pins.telegramX.commit)
foreach ($module in $pins.modules) {
    Run 'git' @('-C',$source,'submodule','update','--init','--depth','1',$module.path)
    $actual = (& git -C (Join-Path $source $module.path) rev-parse HEAD).Trim()
    if ($actual -ne $module.commit) { throw "Calling source pin mismatch: $($module.path)" }
}
$dsp = Join-Path $native 'chromium-rtc-third-party'
if (!(Test-Path -LiteralPath $dsp)) { Run 'git' @('clone','--depth','1','--filter=blob:none','--no-checkout',$pins.chromiumDSP.url,$dsp) }
Run 'git' @('-C',$dsp,'sparse-checkout','set','pffft','rnnoise','jni_zero')
Run 'git' @('-C',$dsp,'fetch','--depth','1','origin',$pins.chromiumDSP.commit)
Run 'git' @('-C',$dsp,'checkout','--detach',$pins.chromiumDSP.commit)
$deps = Join-Path $source 'app/jni/tgvoip/third_party/webrtc_deps/third_party'
New-Item -ItemType Directory -Force -Path $deps | Out-Null
foreach ($folder in @('pffft','rnnoise','jni_zero')) { Copy-Item -LiteralPath (Join-Path $dsp $folder) -Destination $deps -Recurse -Force }
if (!$NdkRoot) { $NdkRoot = Join-Path $native 'android-ndk-r28c' }
if (!(Select-String -LiteralPath (Join-Path $NdkRoot 'source.properties') -Pattern $pins.ndk -SimpleMatch -Quiet)) { throw 'Use the reviewed NDK revision.' }
if (!(Test-Path -LiteralPath (Join-Path $native 'openssl-install/lib/libcrypto.a'))) { throw 'Build the existing reviewed TDLib/OpenSSL recipe first; do not download replacement binaries.' }
$bash = Join-Path $MsysRoot 'usr/bin/bash.exe'
$gitRoot = Split-Path -Parent (Split-Path -Parent (Get-Command git).Source)
$gitBin = (& $bash -c '/usr/bin/cygpath -u "$1"' '_' (Join-Path $gitRoot 'usr/bin')).Trim()
$rootPosix = (& $bash -c '/usr/bin/cygpath -u "$1"' '_' $root).Trim()
$ndkPosix = (& $bash -c '/usr/bin/cygpath -u "$1"' '_' $NdkRoot).Trim()
$env:GIT_POSIX_BIN = $gitBin
Run $bash @("$rootPosix/native/calling/Build-Vpx.sh",$rootPosix,$ndkPosix)
$build = Join-Path $native 'calls-arm64'
$temporary = Join-Path $native 'calls-tmp'
New-Item -ItemType Directory -Force -Path $temporary | Out-Null
$env:TMP = $temporary; $env:TEMP = $temporary; $env:TMPDIR = $temporary
Run 'cmake' @('-S',(Join-Path $root 'native/calling'),'-B',$build,'-G','Ninja',"-DCMAKE_TOOLCHAIN_FILE=$NdkRoot/build/cmake/android.toolchain.cmake",'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release')
Run 'cmake' @('--build',$build,'--target','default_calls','-j',"$Jobs")
$library = Join-Path $build 'libdefault_calls.so'
$out = Join-Path $root 'app/build/generated/callingJni/arm64-v8a'
New-Item -ItemType Directory -Force -Path $out | Out-Null
Copy-Item -LiteralPath $library -Destination $out -Force
$packaged = Join-Path $out 'libdefault_calls.so'
Run (Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe') @('--strip-unneeded',$packaged)
$manifest = @{ abi = 'arm64-v8a'; file = 'libdefault_calls.so'; sha256 = (Get-FileHash -LiteralPath $packaged -Algorithm SHA256).Hash.ToLowerInvariant(); sources = $pins }
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'native/calling-manifest.json') -Encoding utf8
$licenses = Join-Path $root 'app/build/generated/callingAssets/calling-licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'LICENSE') -Destination (Join-Path $licenses 'Telegram-X-LICENSE.txt') -Force
foreach ($module in $pins.modules) {
    $folder = Join-Path $source $module.path
    $name = Split-Path -Leaf $folder
    Get-ChildItem -LiteralPath $folder -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|PATENTS|NOTICE)' } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $licenses "$name-$($_.Name).txt") -Force
    }
}
Copy-Item -LiteralPath (Join-Path $native 'openssl-3.5.9/LICENSE.txt') -Destination (Join-Path $licenses 'OpenSSL-LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $root 'native/calling/sources.json') -Destination $licenses -Force
Write-Output 'Built and packaged source-verified arm64 tgcalls engine. Runtime calling still requires device verification.'
