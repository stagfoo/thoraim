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

    private var downTime = 0L
    private var down = false
    private var injector: ((MotionEvent) -> Boolean)? = null
    var lastError: String? = null
        private set

    /** Async: do not wait for the event to be handled. Waiting would pace us to the game's frame rate. */
    private val modeAsync = 0

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
            injector = { event -> inject.invoke(manager, event, modeAsync) as? Boolean ?: false }
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
            injector = { event -> inject.invoke(global, event, modeAsync) as? Boolean ?: false }
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
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // On a two-screen handheld this is the difference between aiming the
        // game and aiming the other panel.
        setDisplayId(event, displayId)
        try {
            injector?.invoke(event)
        } catch (e: Throwable) {
            lastError = "inject: ${e.message}"
        } finally {
            event.recycle()
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
