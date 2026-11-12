param(
    [Parameter(Mandatory = $true)][string]$MsysRoot,
    [string]$NdkRoot = '',
    [string]$PhpExe = '',
    [string]$CmakeExe = 'cmake',
    [string]$NinjaExe = 'ninja',
    [ValidateSet('arm64-v8a','x86_64','armeabi-v7a','x86')][string[]]$Abis = @('arm64-v8a'),
    [ValidateRange(1,8)][int]$Jobs = 1
)
$ErrorActionPreference = 'Stop'
$CmakeExe = (Get-Command $CmakeExe -ErrorAction Stop).Source
$NinjaExe = (Get-Command $NinjaExe -ErrorAction Stop).Source
$projectRoot = Split-Path -Parent $PSScriptRoot
$work = Join-Path $projectRoot '.native'
$pin = 'c8828d50f5eac2fc7089ce1089d7ce9095c61b5e'
New-Item -ItemType Directory -Path $work -Force | Out-Null
function Run([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Native build command failed: $Executable" }
}
function Fetch([string]$Uri, [string]$Destination, [string]$Hash, [string]$Algorithm = 'SHA256') {
    if (!(Test-Path -LiteralPath $Destination)) { Invoke-WebRequest -Uri $Uri -OutFile $Destination }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ne $Hash) { throw "Checksum mismatch: $Destination" }
}
$env:PATH = "$MsysRoot\mingw64\bin;$MsysRoot\usr\bin;$env:PATH"
foreach ($tool in @('usr/bin/bash.exe','usr/bin/perl.exe','usr/bin/make.exe','usr/bin/zstd.exe','mingw64/bin/g++.exe')) {
    if (!(Test-Path (Join-Path $MsysRoot $tool))) { throw "Missing MSYS2 build prerequisite: $tool" }
}
if (!$NdkRoot) {
    $NdkRoot = Join-Path $work 'android-ndk-r28c'
    if (!(Test-Path $NdkRoot)) {
        Fetch 'https://dl.google.com/android/repository/android-ndk-r28c-windows.zip' (Join-Path $work 'ndk.zip') '086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d' 'SHA1'
        Run 'tar.exe' @('-xf', (Join-Path $work 'ndk.zip'), '-C', $work)
    }
}
$NdkRoot = (Resolve-Path $NdkRoot).Path
if (!(Test-Path (Join-Path $NdkRoot 'build/cmake/android.toolchain.cmake'))) { throw 'Invalid Android NDK root' }
if (!(Select-String -Path (Join-Path $NdkRoot 'source.properties') -Pattern '28.2.13676358' -SimpleMatch -Quiet)) { throw 'This reviewed recipe requires Android NDK 28.2.13676358.' }
if (!$PhpExe) {
    $PhpExe = Join-Path $work 'php/php.exe'
    if (!(Test-Path $PhpExe)) {
        Fetch 'https://downloads.php.net/~windows/releases/archives/php-8.5.12-1-nts-Win32-vs17-x64.zip' (Join-Path $work 'php.zip') '2a36a2edcb7a5b9252f638fd5e4cf84d7f6e066092654220dc0b63e5cf4e9723'
        New-Item -ItemType Directory -Path (Join-Path $work 'php') -Force | Out-Null
        Run 'tar.exe' @('-xf', (Join-Path $work 'php.zip'), '-C', (Join-Path $work 'php'))
    }
}
$gperf = Join-Path $work 'tools/mingw64/bin/gperf.exe'
if (!(Test-Path $gperf)) {
    Fetch 'https://repo.msys2.org/mingw/mingw64/mingw-w64-x86_64-gperf-3.1-2-any.pkg.tar.zst' (Join-Path $work 'gperf.tar.zst') '0b524e9a2fda675d3c09670dc9e1b12f2341c6da7cc853bc4dfe0f3ff5b25cd8'
    New-Item -ItemType Directory -Path (Join-Path $work 'tools') -Force | Out-Null
    Run 'tar.exe' @('-xf', (Join-Path $work 'gperf.tar.zst'), '-C', (Join-Path $work 'tools'))
}
$source = Join-Path $work 'td'
if (!(Test-Path $source)) {
    Run 'git' @('clone','--no-checkout','--depth','1','https://github.com/tdlib/td.git',$source)
    Run 'git' @('-C',$source,'fetch','--depth','1','origin',$pin)
    Run 'git' @('-C',$source,'checkout','--detach',$pin)
}
$actualPin = (& git -C $source rev-parse HEAD).Trim()
if ($actualPin -ne $pin) { throw 'TDLib source pin differs. Preserve/review the existing checkout before continuing.' }
$opensslHash = '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a'
Fetch 'https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz' (Join-Path $work 'openssl.tar.gz') $opensslHash
if (!(Test-Path (Join-Path $work 'openssl-3.5.9'))) { Run 'tar.exe' @('-xf',(Join-Path $work 'openssl.tar.gz'),'-C',$work) }
$hostBuild = Join-Path $work 'host'
Run $CmakeExe @('-S',$source,'-B',$hostBuild,'-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$NinjaExe",'-DCMAKE_BUILD_TYPE=Release','-DTD_GENERATE_SOURCE_FILES=ON',"-DCMAKE_C_COMPILER=$MsysRoot/mingw64/bin/gcc.exe", "-DCMAKE_CXX_COMPILER=$MsysRoot/mingw64/bin/g++.exe", "-DGPERF_EXECUTABLE=$gperf")
Run $CmakeExe @('--build',$hostBuild,'--target','prepare_cross_compiling','--parallel','1')
if (!(Select-String -Path (Join-Path $source 'CMakeLists.txt') -Pattern 'td/telegram/MessagesManager0.cpp' -SimpleMatch -Quiet)) {
    Push-Location $source
    try { Run $PhpExe @('SplitSource.php') } finally { Pop-Location }
}
$env:VECTOR_NDK_WINDOWS = $NdkRoot
$manifest = @{tdlibVersion='1.8.70'; tdlibCommit=$pin; interface='JSONJava'; opensslVersion='3.5.9'; opensslSourceSha256=$opensslHash; ndk='28.2.13676358'; androidApi=29; sourceSplit='official SplitSource.php'; libraries=@()}
foreach ($abi in $Abis) {
    $openssl = Join-Path $work "openssl-$abi"
    if (!(Test-Path (Join-Path $openssl 'lib/libcrypto.a'))) {
        $target = @{ 'arm64-v8a'='android-arm64'; 'x86_64'='android-x86_64'; 'armeabi-v7a'='android-arm'; 'x86'='android-x86' }[$abi]
        $recipe = @'
set -eu
export PATH="/usr/bin:/mingw64/bin:$PATH"
ROOT="$(cd "$(dirname "$0")"; pwd)"
export ANDROID_NDK_ROOT="$(cygpath -u "$VECTOR_NDK_WINDOWS")"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin:$PATH"
cd "$ROOT/openssl-3.5.9"
if [ -f Makefile ]; then make distclean; fi
perl Configure __TARGET__ no-shared no-tests -D__ANDROID_API__=29 --prefix="$(cygpath -m "$ROOT/openssl-__ABI__")" --libdir=lib
make -j__JOBS__ build_libs
make install_dev
'@
        $recipePath = Join-Path $work "openssl-$abi.sh"
        [IO.File]::WriteAllText($recipePath, $recipe.Replace('__TARGET__',$target).Replace('__ABI__',$abi).Replace('__JOBS__',"$Jobs"))
        Run (Join-Path $MsysRoot 'usr/bin/bash.exe') @($recipePath)
    }
    $build = Join-Path $work "android-$abi"
    Run $CmakeExe @('-S',"$source/example/android",'-B',$build,'-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$NinjaExe",'-DCMAKE_BUILD_TYPE=Release',"-DCMAKE_TOOLCHAIN_FILE=$NdkRoot/build/cmake/android.toolchain.cmake", "-DANDROID_ABI=$abi",'-DANDROID_PLATFORM=android-29','-DANDROID_STL=c++_static','-DTD_ANDROID_JSON_JAVA=ON','-DTD_ENABLE_PCH=ON',"-DOPENSSL_ROOT_DIR=$openssl",'-DOPENSSL_USE_STATIC_LIBS=TRUE')
    Run $CmakeExe @('--build',$build,'--target','tdjni','--parallel',"$Jobs")
    $destination = Join-Path $projectRoot "app/src/main/jniLibs/$abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $binary = Join-Path $destination 'libtdjsonjava.so'
    Copy-Item -LiteralPath (Join-Path $build 'libtdjsonjava.so') -Destination $binary
    $manifest.libraries += @{abi=$abi; file='libtdjsonjava.so'; sha256=(Get-FileHash $binary -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $projectRoot 'native/tdlib-manifest.json')
$licenses = Join-Path $projectRoot 'app/src/main/assets/licenses'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
Copy-Item (Join-Path $source 'LICENSE_1_0.txt') (Join-Path $licenses 'TDLib.txt')
Copy-Item (Join-Path $work 'openssl-3.5.9/LICENSE.txt') (Join-Path $licenses 'OpenSSL.txt')
Copy-Item (Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/NOTICE') (Join-Path $licenses 'Android-NDK-runtime-notices.txt')
Write-Host 'Built official TDLib Android libraries. Review native/tdlib-manifest.json before distributing.'
