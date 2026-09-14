#!/usr/bin/env bash
# Builds the daemon and drops it where the APK will carry it.
#
# It lands in jniLibs as libthoraim.so even though it is an executable, not a
# library. That is not a cosmetic choice: since Android 10 an app cannot exec
# anything out of its own data directory, and the one place it still can is the
# native library directory — which the system populates only from files named
# lib*.so inside the APK. Renaming it is the price of being able to run it.
set -euo pipefail
cd "$(dirname "$0")/.."

ndk_root="${ANDROID_NDK_HOME:-$HOME/development/android-sdk/ndk}"
if [ -d "$ndk_root" ] && [ ! -x "$ndk_root/toolchains" ]; then
  ndk=$(ls -d "$ndk_root"/*/ 2>/dev/null | sort -V | tail -1)
else
  ndk="$ndk_root"
fi
ndk="${ndk%/}"

cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
if [ ! -x "$cc" ]; then
  echo "No NDK clang at $cc" >&2
  exit 1
fi

out="app/src/main/jniLibs/arm64-v8a/libthoraim.so"
mkdir -p "$(dirname "$out")"

"$cc" -O2 -Wall -Wextra -Werror -std=c11 -I native \
  -o "$out" \
  native/main.c native/aim.c native/devices.c native/uinput.c native/config.c \
  -lm

"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$out"
echo "built $out ($(stat -c%s "$out") bytes)"
