package com.thoraim.app

import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * The loop, running as shell inside a Shizuku service.
 *
 * Two threads on purpose. One blocks reading the gamepad, because a blocking
 * read is the lowest-latency way to learn a stick moved — polling would add
 * half a tick of lag for nothing. The other ticks at a fixed rate and injects,
 * because touch has to be a smooth stream whether or not the pad reported
 * anything since the last frame: a stick held still still needs the finger to
 * keep moving.
 */
class AimService : IAimService.Stub() {

    @Volatile private var running = false
    @Volatile private var enabled = true
    @Volatile private var settings = Settings()

    @Volatile private var rawX = 0
    @Volatile private var rawY = 0
    @Volatile private var haveX = false
    @Volatile private var haveY = false

    private val rangeX = AxisRange()
    private val rangeY = AxisRange()

    private var reader: Thread? = null
    private var ticker: Thread? = null
    private val injector = TouchInjector()
    private var engine = AimEngine(settings)

    @Volatile private var report = "not started"
    @Volatile private var screenWidth = 1080f
    @Volatile private var screenHeight = 1920f
    @Volatile private var displayId = 0

    override fun isRunning(): Boolean = running

    override fun status(): String = report

    /**
     * What the stick is doing, right now.
     *
     * Worth its weight the first time this runs on a device: reading the pad
     * and injecting touch are two entirely separate privileges that fail in
     * entirely separate ways, and without this a dead aim looks identical
     * whichever half broke. If the numbers here move, evdev is working and the
     * problem is injection.
     */
    override fun probe(): String {
        if (!running) return "not running"
        if (!haveX && !haveY) {
            return "no axis events yet — move the right stick"
        }
        val nx = rangeX.normalise(rawX)
        val ny = rangeY.normalise(rawY)
        val mag = kotlin.math.hypot(nx, ny)
        return buildString {
            append("stick %+.2f, %+.2f  (|%.2f|)".format(nx, ny, mag))
            append(if (mag <= settings.deadzone) "  in deadzone" else "  live")
            append("\nraw $rawX, $rawY   range x=$rangeX y=$rangeY")
            append("\nfinger ${if (injector.isDown) "down" else "up"}")
            append(", aiming ${if (enabled) "on" else "off (R3)"}")
            injector.lastError?.let { append("\ninject error: $it") }
        }
    }

    override fun start(configText: String): String {
        if (running) return report
        settings = Settings.parse(configText)
        engine = AimEngine(settings)
        screenWidth = settings.screenWidth
        screenHeight = settings.screenHeight
        displayId = settings.displayId
        enabled = settings.startEnabled

        val pad = Evdev.findGamepad()
            ?: return "no gamepad found in /proc/bus/input/devices".also { report = it }
        val (codeX, codeY) = pad.rightStick
            ?: return "${pad.name} has no right stick".also { report = it }

        val readRanges = try {
            AxisRanges.read(pad.name, codeX, codeY, rangeX, rangeY)
        } catch (e: Throwable) {
            false
        }

        if (!injector.prepare()) {
            return "cannot reach injectInputEvent: ${injector.lastError}".also { report = it }
        }

        running = true
        startReader(pad.path, codeX, codeY)
        startTicker()

        report = buildString {
            append("pad ${pad.name} at ${pad.path}, ")
            append("axes $codeX/$codeY, ")
            append("range x=$rangeX y=$rangeY")
            if (!readRanges) append(" (Android did not report ranges)")
            append(", screen ${screenWidth.toInt()}x${screenHeight.toInt()} on display $displayId")
            append(", ${if (enabled) "aiming" else "idle"}")
        }
        return report
    }

    override fun stop() {
        running = false
        reader?.interrupt()
        ticker?.interrupt()
        reader = null
        ticker = null
        if (injector.isDown) injector.lift(0f, 0f, displayId)
        report = "stopped"
    }

    override fun reconfigure(configText: String) {
        val next = Settings.parse(configText)
        settings = next
        screenWidth = next.screenWidth
        screenHeight = next.screenHeight
        displayId = next.displayId
        // Handed to the running engine rather than replacing it, so a slider
        // does not drop the stroke that is currently down.
        engine.reconfigure(next)
    }

    override fun destroy() {
        stop()
        System.exit(0)
    }

    private fun startReader(path: String, codeX: Int, codeY: Int) {
        reader = thread(name = "thoraim-evdev", isDaemon = true) {
            try {
                Evdev.open(path).use { stream ->
                    val buffer = ByteArray(Evdev.EVENT_SIZE * 64)
                    while (running) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        for (event in Evdev.parse(buffer, read)) {
                            when {
                                event.type == Evdev.EV_ABS && event.code == codeX -> {
                                    rangeX.observe(event.value); rawX = event.value; haveX = true
                                }
                                event.type == Evdev.EV_ABS && event.code == codeY -> {
                                    rangeY.observe(event.value); rawY = event.value; haveY = true
                                }
                                event.type == Evdev.EV_KEY && event.value == 1 &&
                                    settings.toggleButton != 0 &&
                                    event.code == settings.toggleButton -> {
                                    enabled = !enabled
                                    if (!enabled) {
                                        // Let go at once. A finger left down on
                                        // a menu is how a toggle becomes a stuck
                                        // touch you cannot clear.
                                        releaseNow()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                report = "reading $path failed: ${e.message}"
                running = false
            }
        }
    }

    private fun startTicker() {
        ticker = thread(name = "thoraim-tick", isDaemon = true) {
            var last = SystemClock.uptimeMillis()
            while (running) {
                val period = (1000L / settings.pollHz).coerceAtLeast(1L)
                try {
                    Thread.sleep(period)
                } catch (e: InterruptedException) {
                    break
                }
                if (!running) break

                val now = SystemClock.uptimeMillis()
                var dt = (now - last) / 1000f
                last = now
                // A tick that was descheduled for a long time must not teleport
                // the finger across the screen.
                if (dt <= 0f || dt > 0.25f) dt = period / 1000f

                if (!enabled || !haveX || !haveY) continue

                val step = engine.step(
                    rangeX.normalise(rawX),
                    rangeY.normalise(rawY),
                    dt,
                    now,
                )
                val px = step.x * screenWidth
                val py = step.y * screenHeight

                when (step.action) {
                    AimEngine.Action.PRESS -> injector.press(px, py, displayId)
                    AimEngine.Action.MOVE -> injector.drag(px, py, displayId)
                    AimEngine.Action.LIFT -> injector.lift(px, py, displayId)
                    AimEngine.Action.RESTART -> {
                        injector.lift(px, py, displayId)
                        injector.press(px, py, displayId)
                    }
                    AimEngine.Action.NONE -> Unit
                }
            }
        }
    }

    private fun releaseNow() {
        engine.reset()
        if (injector.isDown) {
            injector.lift(0.5f * screenWidth, 0.5f * screenHeight, displayId)
        }
    }
}
