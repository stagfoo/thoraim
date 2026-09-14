# thoraim

Aims NIKKE with the right stick on an AYN Thor. **No root** — it runs on
[Shizuku](https://shizuku.rikka.app/).

The game reads aiming as a finger dragging across the screen. It has no idea a
gamepad exists, and there is no button to map to, so the only way in is to be
that finger.

## Why this needs privileges, and which ones

Two things are needed, and an ordinary app uid has neither:

**Reading the stick while the game is in front.** Android delivers key and
motion events to the *focused* window, so nothing in the background sees a stick
through the normal API. `/dev/input/event*` sits below all that — the kernel has
no concept of focus, and every reader with permission sees every event.
`adb shell getevent` printing your stick while a game is foreground is the same
mechanism. The only catch is that those nodes are `crw-rw---- root input`, and
an app uid is not in the `input` group.

**Injecting the touch.** `InputManager.injectInputEvent` is gated on
`INJECT_EVENTS`, a signature permission. `adb shell input tap` works because
shell holds it.

Shell has both. Shizuku runs a service of ours *as* shell, so it gets both
without root. That is the whole trick — focus never enters into it.

For comparison, the other non-root routes and why they do not work here:

| route | reads analog sticks in background? |
|---|---|
| normal app (`onGenericMotionEvent`) | no — focus-bound |
| AccessibilityService (`onKeyEvent`) | no — KeyEvents only, no axes |
| focusable overlay window | yes, but it takes focus off the game |
| Shizuku → evdev | **yes** |

## The thing that actually matters

A drag has an end. When the finger reaches the edge of the area it may use, it
has to lift, jump back and press again — and that hitch is what you feel on a
long sweep. Two settings decide how often it happens:

| region | stroke starts | longest stroke | hitches per 3s sweep |
|---|---|---|---|
| full screen | far edge | **0.880 screens** | 7 |
| full screen | centre | 0.477 | 13 |
| 40% box | centre | 0.165 | 35 |

Measured, not guessed: `AimEngineTest.travel table` prints exactly that, so it
cannot drift from the code.

Starting each stroke against the *far* side of the region, facing the way the
stick is pointing, is worth as much as making the region bigger. Starting in the
middle throws away half the travel before you have moved.

## Setup

1. Install Shizuku and start it — wireless debugging is the no-root route, and
   it has to be redone after a reboot.
2. Install thoraim, press **Start**, allow it.
3. Click the right stick (R3) to toggle aiming, so menus and normal touch keep
   working.

Sliders apply live over Binder without dropping the stroke that is down.

**If it does not work**, press *Watch the stick* first. Reading the pad and
injecting touch are two separate privileges that fail separately, and a dead aim
looks identical whichever one broke. If those numbers move when you push the
stick, evdev is fine and the problem is injection — which the same readout
reports.

## Layout

```
AimEngine.kt      the aiming maths — deadzone, curve, anchoring, restarts
Evdev.kt          finds the pad by capability, parses raw input events
AxisRanges.kt     axis ranges from InputDevice (EVIOCGABS is out of reach)
TouchInjector.kt  MotionEvents into the dispatcher, as shell
AimService.kt     the loop, running in the Shizuku service
Privilege.kt      Shizuku state and the binding
MainActivity.kt   sliders, start/stop, status
```

`AimEngine` and `Evdev` have no Android types in them, which is why 32 unit
tests can cover the parts that decide how aiming feels without a handheld.

## Known risks, untested on hardware

- **Which screen gets aimed.** The Thor has two. The app measures its own window
  and passes the size and display id to the service, so injection targets the
  panel you were looking at — but `MotionEvent.setDisplayId` is hidden API and
  absent on older releases, where it falls back to the default display.
- **Hidden-API access from the service.** `injectInputEvent` is reached by
  reflection, with a fallback to `InputManagerGlobal` for Android 14+. If both
  fail, Start says so rather than pretending to work.
- **Whether the pad presents as one evdev node.** The Thor routes its controller
  through InputPlumber; Start reports the device, node and axes it picked.
