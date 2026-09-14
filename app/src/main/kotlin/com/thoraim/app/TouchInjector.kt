package com.thoraim.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * The virtual finger.
 *
 * Without root there is no `/dev/uinput`, so instead of inventing a touchscreen
 * this hands MotionEvents straight to the input dispatcher — which is exactly
 * what `adb shell input tap` does, and it works for the same reason: the caller
 * is running as shell, and shell holds INJECT_EVENTS.
 *
 * Reflection because `InputManager.injectInputEvent` is hidden API. The
 * signature has been stable since it was added, and the alternative is spawning
 * `/system/bin/input` per event, which would be a process fork per frame.
 */
class TouchInjector {

    /**
     * What kind of pointer to pretend to be.
     *
     * Three, because which one a game listens to is not something that can be
     * reasoned out from here. Unity's input handling in particular treats these
     * differently depending on which input module the game was built with, and
     * NIKKE is a Unity game.
     */
    enum class Mode(val source: Int, val toolType: Int, val label: String) {
        TOUCH(InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, "touchscreen"),
        MOUSE(InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, "mouse"),
        STYLUS(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, "stylus"),
    }

    var mode: Mode = Mode.TOUCH

    /** Whether the last injection was accepted. Null before anything was sent. */
    var lastAccepted: Boolean? = null
        private set

    private var downTime = 0L
    private var down = false
    private var injector: ((MotionEvent) -> Boolean)? = null
    var lastError: String? = null
        private set

    /**
     * Async: do not wait for the event to be handled. Waiting would pace every
     * injection to the game's frame rate, which is the thing latency is being
     * spent on.
     *
     * The `input` command uses WAIT_FOR_FINISH instead, so that is kept as a
     * fallback: if async is refused on this build, the slower mode is still
     * better than nothing.
     */
    private val modeAsync = 0
    private val modeWaitForFinish = 2
    private var injectMode = modeAsync

    fun prepare(): Boolean {
        if (injector != null) return true
        try {
            val managerClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = managerClass.getMethod("getInstance")
            val manager = getInstance.invoke(null)
            val inject = managerClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            injector = { event -> inject.invoke(manager, event, injectMode) as? Boolean ?: false }
            return true
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        }

        // Android 14 moved the instance behind InputManagerGlobal; the injecting
        // method itself did not change.
        try {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInstance = globalClass.getMethod("getInstance")
            val global = getInstance.invoke(null)
            val inject = globalClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            injector = { event -> inject.invoke(global, event, injectMode) as? Boolean ?: false }
            lastError = null
            return true
        } catch (e: Throwable) {
            lastError = "${lastError ?: ""} / ${e.javaClass.simpleName}: ${e.message}"
            return false
        }
    }

    fun press(x: Float, y: Float, displayId: Int) {
        downTime = SystemClock.uptimeMillis()
        down = true
        send(MotionEvent.ACTION_DOWN, x, y, displayId)
    }

    fun drag(x: Float, y: Float, displayId: Int) {
        if (!down) return
        send(MotionEvent.ACTION_MOVE, x, y, displayId)
    }

    fun lift(x: Float, y: Float, displayId: Int) {
        if (!down) return
        send(MotionEvent.ACTION_UP, x, y, displayId)
        down = false
    }

    val isDown: Boolean get() = down

    private fun send(action: Int, x: Float, y: Float, displayId: Int) {
        val now = SystemClock.uptimeMillis()

        // The long form of obtain, because the short one cannot set buttonState
        // or toolType — and a mouse with no button held is a hover, which is
        // not a click, which is the entire difference between the modes.
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = mode.toolType
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            }
        )
        val buttons = when {
            mode != Mode.TOUCH && action != MotionEvent.ACTION_UP ->
                MotionEvent.BUTTON_PRIMARY
            else -> 0
        }

        val event = MotionEvent.obtain(
            downTime, now, action, 1, properties, coords,
            0, buttons, 1f, 1f, 0, 0, mode.source, 0,
        )
        // On a two-screen handheld this is the difference between aiming the
        // game and aiming the other panel.
        setDisplayId(event, displayId)
        try {
            lastAccepted = injector?.invoke(event)
        } catch (e: Throwable) {
            lastError = "inject: ${e.message}"
            lastAccepted = false
        } finally {
            event.recycle()
        }
    }

    /**
     * Drags across the middle of the screen once, for checking whether anything
     * lands at all.
     *
     * Separate from aiming on purpose: if this moves a list in any scrollable
     * app, injection works and the problem is the stick, the region or the
     * game. If it does nothing anywhere, injection is the problem and nothing
     * about the aiming settings matters yet.
     */
    fun testDrag(width: Float, height: Float, displayId: Int): String {
        if (!prepare()) return "cannot reach injectInputEvent: ${'$'}lastError"

        val y = height * 0.5f
        press(width * 0.25f, y, displayId)
        val accepted = lastAccepted
        for (i in 1..20) {
            Thread.sleep(12)
            drag(width * (0.25f + 0.5f * i / 20f), y, displayId)
        }
        Thread.sleep(12)
        lift(width * 0.75f, y, displayId)

        return buildString {
            append("sent a ${'$'}{mode.label} drag across the middle of display ${'$'}displayId")
            append(" (${'$'}{(width * 0.25f).toInt()} to ${'$'}{(width * 0.75f).toInt()} at y ${'$'}{y.toInt()})")
            append("\ninjectInputEvent returned ")
            append(
                when (accepted) {
                    true -> "true — the system accepted it"
                    false -> "false — the system refused it"
                    null -> "nothing"
                }
            )
            lastError?.let { append("\nerror: ${'$'}it") }
        }
    }

    private fun setDisplayId(event: MotionEvent, displayId: Int) {
        if (displayId < 0) return
        try {
            MotionEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        } catch (e: Throwable) {
            // Older releases have no such method and inject to the default
            // display, which is the right guess anyway.
        }
    }
}
