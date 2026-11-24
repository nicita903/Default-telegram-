#!/usr/bin/env bash
set -euo pipefail
root="$1"
ndk="$2"
mkdir -p "$root/.native/calls-tmp"
export TMPDIR="$root/.native/calls-tmp"
export TMP="$(cygpath -w "$TMPDIR")"
export TEMP="$TMP"
export PATH="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin:/usr/bin:/mingw64/bin:${GIT_POSIX_BIN:-/e/programs/Git/usr/bin}:$PATH"
export CC="clang.exe --target=aarch64-linux-android29"
export CXX="clang++.exe --target=aarch64-linux-android29"
export AR="llvm-ar.exe"
export LD="clang++.exe --target=aarch64-linux-android29"
export STRIP="llvm-strip.exe"
export NM="llvm-nm.exe"
mkdir -p "$root/.native/calls-vpx"
cd "$root/.native/calls-vpx"
"$root/.native/telegram-x-calls/app/jni/third_party/libvpx/configure" --target=arm64-android-gcc --disable-examples --disable-tools --disable-docs --disable-unit-tests --disable-shared --enable-pic
make -j4 libvpx.a
