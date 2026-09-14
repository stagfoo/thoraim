# thoraim

Aims NIKKE with the right stick on an AYN Thor. **No root, no Shizuku** — just
Accessibility, plus "draw over other apps" on Android 13 and below.

The game reads aiming as a finger dragging across the screen. It has no idea a
gamepad exists, and there is no button to map to, so the only way in is to be
that finger.

## How it works, and why this way

The game reads aiming as a finger dragging across the screen. It has no idea a
gamepad exists and there is no button to map to, so the only way in is to be
that finger. Two halves, each with its own obstacle.

**Putting touch into another app.** `AccessibilityService.dispatchGesture` is
the only route an ordinary app has. It is not an event you inject — it is a
stroke with a *duration* that the system plays out, so a continuous drag is a
chain: each segment declares `willContinue` and the next starts from the
previous one's end when it reports finished. That chaining also sets the pace;
asking where the stick is more often than a segment completes only queues work
that cannot be delivered.

**Reading the stick while the game is in front.** Android delivers gamepad
motion to the *focused* window and nothing else. Since Android 14 an
accessibility service can ask the system for motion events directly, with no
window and no focus. Before that the only way is a window that holds focus, so
there is a 2×2px invisible overlay for that case — it takes key focus off the
game, which is a real cost, and the reason it is the fallback rather than the
design.

This is the same shape as the mappers that work on this device. An earlier build
used Shizuku to read `/dev/input` and call `injectInputEvent` as shell, which is
lower latency and not obviously wrong — but it did not work here, and being
available and accepted beats being fast.

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

1. Settings > Accessibility > **thoraim** > on.
2. On Android 13 and below, allow **draw over other apps** as well.
3. Press **Start**. Click the right stick (R3) to toggle aiming, so menus and
   normal touch keep working.

Sliders apply live without dropping the stroke that is down.

## If it does not work

Three buttons, in this order. They exist because reading the pad and injecting
touch are separate privileges that fail separately, and a dead aim looks
identical whichever one broke.

**Diagnose** — one screenful covering every thing that could be wrong: the uid
the service got, whether `/dev/input` is readable and which nodes, which devices
look like a pad and what axes they carry, what Android thinks is attached, and
whether `injectInputEvent` resolved.

**Watch the stick** — live axis values. If these move when you push the stick,
evdev is working and the problem is downstream.

**Self test** — injects a drag onto thoraim's own window and reports whether it
arrived. This is the one check that needs no interpretation: the app establishes
a fact about itself rather than asking you whether a list twitched. If events
arrive, injection works and anything still wrong is the game or the stick. If
nothing arrives, injection is not reaching the dispatcher and no aiming setting
matters yet.

**Test drag** — the same drag, but aimed at whatever is in front. Use it once the
self test passes, to find out whether the game in particular ignores it.

### Pretend to be

Injected events can claim to come from a touchscreen, a mouse or a stylus. NIKKE
is Unity, and which of the three its input module listens to is not something
that can be settled from outside the device — try each against *Test drag*.

Note that a mouse is not a shortcut around the travel problem: the Android
emulators that advertise mouse aiming for NIKKE all *synthesise touch*
(BlueStacks says so outright — its keymapper "emulates the touch and tap you
would make on your mobile device"), and their shooter modes hit the same
recentring hitch and solve it the same way.

## Layout

```
AimEngine.kt                 the aiming maths — deadzone, curve, anchoring, restarts
GestureDriver.kt             a finger made of chained accessibility strokes
AimAccessibilityService.kt   reads the stick, runs the loop
StickOverlay.kt              the focus-catching window, Android 13 and below
MainActivity.kt              sliders, start/stop, diagnostics
```

`AimEngine` has no Android types in it, which is why 17 unit tests can cover
the parts that decide how aiming feels without a handheld.

## Known risks, untested on hardware

- **Which screen gets aimed.** The Thor has two. The app measures its own window
  and passes the display id to `GestureDescription.Builder.setDisplayId` — a
  public API, unlike the equivalent for injected events, so this is on firmer
  ground than it was.
- **Gesture pacing.** Segments are 12–40ms. Too short and the system drops them
  rather than playing them, which reads as the aim sticking; too long and aim
  lags. The poll-rate slider drives it, so it can be found by feel.
- **Whether motion events arrive on Android 14+.** Diagnose reports which of the
  two routes is in use and whether any stick movement has been seen.
