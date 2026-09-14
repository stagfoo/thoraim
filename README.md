# thoraim

Aims NIKKE with the right stick on an AYN Thor.

The game reads aiming as a finger dragging across the screen. It has no idea a
gamepad exists, and there is no button to map to — the input it wants is a
continuous drag. So this reads the right stick off evdev and *is* that finger: a
virtual touchscreen on `/dev/uinput`, pressed down and dragged around.

## The thing that actually matters

A drag has an end. When the virtual finger reaches the edge of the area it is
allowed to use, it has to lift, jump back, and press again — and that hitch is
what you feel on a long sweep. Two settings decide how often it happens:

| region | stroke start | longest stroke | hitches per 3s sweep |
|---|---|---|---|
| full screen | far edge | **0.88 screens** | 7 |
| full screen | centre | 0.48 | 13 |
| 40% box | centre | 0.33 | 18 |

Measured, not guessed — `scripts/test-native.sh` prints that table.

Starting each stroke against the *far* side of the region, facing the way the
stick is pointing, is worth as much as making the region bigger. Starting in the
middle throws away half the travel before you have moved.

## Building

```sh
./scripts/test-native.sh     # the aim maths, on this machine
./scripts/build-native.sh    # the arm64 daemon into jniLibs
./gradlew :app:assembleRelease
```

The daemon is packaged as `lib/arm64-v8a/libthoraim.so`. It is an executable,
not a library: that name is the only way to get a runnable file onto an Android
device's filesystem with the exec bit set, since an app may not exec anything
out of its own data directory.

## Running

Needs root. `/dev/uinput` is root-only on every Android device — ADB shell
cannot open it either — so there is no unrooted path to a virtual touchscreen.

Open the app, press **Start**, grant su. Click the right stick (R3) to toggle
aiming, so menus and normal touch still work. Sliders apply live: the daemon
re-reads its config on SIGHUP without dropping the finger that is down.

- config: `/data/local/tmp/thoraim.conf`
- log: `/data/local/tmp/thoraim.log`

## Layout

```
native/aim.c        the aiming maths — deadzone, curve, anchoring, restarts
native/aim.h
native/devices.c    finds the gamepad and the right screen, by capability
native/uinput.c     the virtual touchscreen
native/config.c     tuning values, clamped on load
native/main.c       epoll loop: evdev in, touch out
native/test_aim.c   runs on a desktop, against numbers
app/                Kotlin UI: eight sliders, start/stop, log
```

`aim.c` has no device code in it at all, which is why it can be tested without
a handheld. Everything that decides how aiming *feels* lives there.

## Known risks, untested on hardware

- **Which screen gets aimed.** The Thor has two, and Android associates an input
  device with a display partly by its dimensions. The virtual touchscreen copies
  the real one's ranges and the app passes its own window width as a hint, but
  whether that lands on the top panel is not something that can be checked from
  here.
- **Device detection.** Gamepad and touchscreen are found by capability rather
  than by hardcoded `eventN` paths, but the Thor routes its pad through
  InputPlumber and that may present differently under Android than under
  ROCKNIX. The log prints exactly what it picked.
