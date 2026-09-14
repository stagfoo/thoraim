package com.thoraim.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import kotlin.concurrent.thread

/**
 * The whole thing, without root and without Shizuku.
 *
 * Accessibility is the only route Android offers an ordinary app into another
 * app's touch input, and it turns out to be the one that works here — which is
 * worth stating plainly, because it is *slower* than injecting events as shell
 * and I built that first. Being available and accepted beats being fast.
 *
 * Reading the stick is the other half. An accessibility service can ask for
 * motion events outright since Android 14, which needs no window and steals no
 * focus. Before that the only way is a focusable overlay, which does take focus
 * off the game — acceptable, since a game does not need key focus to run, but
 * not something to do when the system will simply hand them over.
 */
class AimAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AimAccessibilityService? = null

        /** Whether the user has turned this on in Settings. */
        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return flat.split(':').any {
                it.equals(
                    "${context.packageName}/${AimAccessibilityService::class.java.name}",
                    ignoreCase = true,
                )
            }
        }
    }

    private lateinit var driver: GestureDriver
    private var engine = AimEngine(Settings())
    private var settings = Settings()

    @Volatile private var stickX = 0f
    @Volatile private var stickY = 0f
    @Volatile private var sawStick = false
    @Volatile private var enabled = true
    @Volatile private var looping = false
    private var loop: Thread? = null

    @Volatile var report: String = "not started"
        private set
    @Volatile private var motionSource = "none"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        driver = GestureDriver(this)

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                serviceInfo = serviceInfo.apply {
                    setMotionEventSources(
                        InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD
                    )
                }
                motionSource = "accessibility motion events (Android 14+)"
            } catch (e: Throwable) {
                motionSource = "motion events refused: ${e.message}"
            }
        } else {
            motionSource = "needs the overlay: Android ${Build.VERSION.SDK_INT} " +
                "has no accessibility motion events"
        }

        report = "connected — $motionSource"
    }

    override fun onDestroy() {
        stop()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // --------------------------------------------------------------- input

    /**
     * Android 14+: the system hands over gamepad motion without a window and
     * without focus. Returning false so the game still gets it — taking it away
     * would break anything that reads the pad itself.
     */
    override fun onMotionEvent(event: MotionEvent) {
        readStick(event)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != settings.toggleKeyCode()) return false
        enabled = !enabled
        if (!enabled) releaseNow()
        return false
    }

    /** Called by the overlay on Android 13 and below, and by [onMotionEvent]. */
    fun readStick(event: MotionEvent) {
        val sources = event.source
        val isPad = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        if (!isPad) return

        // Which pair carries the right stick differs by pad, so both are read
        // and whichever is actually moving wins. Android has already normalised
        // these to -1..1, which is the one real benefit of coming in this way
        // rather than off evdev.
        val rx = event.getAxisValue(MotionEvent.AXIS_RX)
        val ry = event.getAxisValue(MotionEvent.AXIS_RY)
        val z = event.getAxisValue(MotionEvent.AXIS_Z)
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)

        val useZ = (kotlin.math.abs(z) + kotlin.math.abs(rz)) >
            (kotlin.math.abs(rx) + kotlin.math.abs(ry))

        stickX = if (useZ) z else rx
        stickY = if (useZ) rz else ry
        sawStick = true
    }

    // ---------------------------------------------------------------- loop

    fun start(next: Settings): String {
        settings = next
        engine = AimEngine(next)
        enabled = next.startEnabled
        driver.displayId = next.displayId
        driver.segmentMs = (1000L / next.pollHz).coerceIn(12L, 40L)

        if (looping) return report
        looping = true
        loop = thread(name = "thoraim-aim", isDaemon = true) {
            var last = android.os.SystemClock.uptimeMillis()
            while (looping) {
                try {
                    Thread.sleep(driver.segmentMs)
                } catch (e: InterruptedException) {
                    break
                }
                val now = android.os.SystemClock.uptimeMillis()
                var dt = (now - last) / 1000f
                last = now
                if (dt <= 0f || dt > 0.25f) dt = driver.segmentMs / 1000f

                if (!enabled || !sawStick) continue

                val step = engine.step(stickX, stickY, dt, now)
                val px = step.x * settings.screenWidth
                val py = step.y * settings.screenHeight

                when (step.action) {
                    AimEngine.Action.PRESS,
                    AimEngine.Action.MOVE,
                    AimEngine.Action.RESTART -> driver.moveTo(px, py)
                    AimEngine.Action.LIFT -> driver.release()
                    AimEngine.Action.NONE -> Unit
                }
            }
        }

        report = "running — $motionSource, ${settings.screenWidth.toInt()}x" +
            "${settings.screenHeight.toInt()} on display ${settings.displayId}"
        return report
    }

    fun stop() {
        looping = false
        loop?.interrupt()
        loop = null
        releaseNow()
        report = "stopped"
    }

    fun reconfigure(next: Settings) {
        settings = next
        driver.displayId = next.displayId
        driver.segmentMs = (1000L / next.pollHz).coerceIn(12L, 40L)
        engine.reconfigure(next)
    }

    private fun releaseNow() {
        engine.reset()
        if (this::driver.isInitialized) driver.abandon()
    }

    val isRunning: Boolean get() = looping

    fun probe(): String = buildString {
        if (!sawStick) {
            append("no gamepad motion seen yet.\n")
            append(motionSource)
            if (Build.VERSION.SDK_INT < 34) {
                append("\nTurn on the overlay and keep it showing — before ")
                append("Android 14 that is the only way a background app sees ")
                append("a stick at all.")
            }
            return@buildString
        }
        val mag = kotlin.math.hypot(stickX, stickY)
        append("stick %+.2f, %+.2f  (|%.2f|)".format(stickX, stickY, mag))
        append(if (mag <= settings.deadzone) "  in deadzone" else "  live")
        append("\naiming ${if (enabled) "on" else "off"}, source: $motionSource")
        append("\n")
        append(if (this@AimAccessibilityService::driver.isInitialized) driver.report() else "no driver")
    }

    /** Sends one drag across the middle, to prove gestures land at all. */
    fun testDrag(width: Float, height: Float, displayId: Int): String {
        driver.displayId = displayId
        val y = height * 0.5f
        driver.moveTo(width * 0.25f, y)
        Thread.sleep(80)
        for (i in 1..8) {
            driver.moveTo(width * (0.25f + 0.5f * i / 8f), y)
            Thread.sleep(40)
        }
        driver.release()
        Thread.sleep(120)
        return "sent a gesture drag across display $displayId\n${driver.report()}"
    }
}
