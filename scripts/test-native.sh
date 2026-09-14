#!/usr/bin/env bash
# The aim maths, checked on this machine.
#
# Only the arithmetic: the evdev and uinput halves need the device. Everything
# that decides how aiming feels is in aim.c, and none of it needs a handheld.
set -euo pipefail
cd "$(dirname "$0")/.."

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

cc -O2 -Wall -Wextra -Werror -std=c11 -I native -o "$out/test_aim" \
  native/test_aim.c native/aim.c native/config.c -lm
"$out/test_aim"
