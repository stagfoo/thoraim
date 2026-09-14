#!/usr/bin/env bash
# Builds everything from source and publishes it, or publishes nothing.
set -euo pipefail
cd "$(dirname "$0")/.."

trap 'status=$?; if [ $status -ne 0 ]; then
  echo "!! release.sh FAILED (exit $status). Nothing was published." >&2
  echo "!! Do not finish this by hand: the APK in app/build may be from an" >&2
  echo "!! earlier version, and publishing it skips the check that catches that." >&2
fi' EXIT

gradle_file="app/build.gradle.kts"
current=$(grep -oP 'versionName = "\K[^"]+' "$gradle_file")
code=$(grep -oP 'versionCode = \K[0-9]+' "$gradle_file")
next="${current%.*}.$(( ${current##*.} + 1 ))"
next_code=$(( code + 1 ))
echo "==> thoraim $current -> $next"

sed -i "s/versionName = \"$current\"/versionName = \"$next\"/" "$gradle_file"
sed -i "s/versionCode = $code/versionCode = $next_code/" "$gradle_file"

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
aapt2=$(ls -d "${ANDROID_HOME:-$HOME/development/android-sdk}"/build-tools/*/aapt2 | sort -V | tail -1)

# Read the version back out of the APK that is about to be uploaded. Trusting
# the build to have picked up the bump is exactly how a previous version's APK
# gets published under a new tag.
badging=$("$aapt2" dump badging "$apk")
built=$(printf '%s\n' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | sed -n 1p)
if [ "$built" != "$next" ]; then
  echo "APK says $built, expected $next" >&2
  exit 1
fi

# R8 strips anything it cannot trace a call to, and Shizuku loads the service
# by name from another process — so a missing keep rule builds, installs, and
# fails only when Start is pressed. Checked here rather than on the device.
dexdump=$(ls -d "${ANDROID_HOME:-$HOME/development/android-sdk}"/build-tools/*/dexdump | sort -V | tail -1)
work=$(mktemp -d)
unzip -o -q "$apk" "classes*.dex" -d "$work"
kept=$(for d in "$work"/*.dex; do "$dexdump" "$d" 2>/dev/null; done | grep -c "Lcom/thoraim/app/AimService;" || true)
rm -rf "$work"
if [ "$kept" -eq 0 ]; then
  echo "R8 stripped AimService — Shizuku would fail to start it" >&2
  exit 1
fi

git add -A
git commit -m "Release $next"
git push origin HEAD

release_apk="thoraim-$next.apk"
cp "$apk" "$release_apk"
gh release create "$next" "$release_apk" \
  --title "thoraim $next" \
  --notes "Right-stick aiming for NIKKE on the AYN Thor. Needs Shizuku, not root." \
  --target "$(git rev-parse HEAD)"
rm -f "$release_apk"

echo "==> APK verified as $next"
gh release view "$next" --json url -q .url
