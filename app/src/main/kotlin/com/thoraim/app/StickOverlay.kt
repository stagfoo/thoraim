package com.thoraim.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * A window whose only job is to be focused, so a stick can be read.
 *
 * Only needed before Android 14. From 14 an accessibility service can ask the
 * system for motion events directly and none of this is necessary.
 *
 * Before that there is no other way: Android delivers gamepad motion to the
 * *focused* window and nothing else, and an accessibility service is not a
 * window. So this is one, a few pixels across and all but invisible, focusable
 * so events arrive and untouchable so every touch still reaches the game.
 *
 * It does take key focus off the game. That is a real cost and the reason this
 * is the fallback rather than the design — but a game does not need key focus
 * to run, which is why the mappers that work this way work.
 */
class StickOverlay(private val context: Context) {

    private var manager: WindowManager? = null
    private var view: View? = null

    val showing: Boolean get() = view != null

    companion object {
        fun canDraw(context: Context): Boolean =
            Settings.canDrawOverlays(context)
    }

    fun show(onMotion: (MotionEvent) -> Unit): Boolean {
        if (view != null) return true
        if (!canDraw(context)) return false

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false

        val target = object : View(context) {
            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                onMotion(event)
                // Not consumed: anything else that wants the pad still gets it.
                return false
            }

            override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean = false
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // No background at all. An invisible window that still takes focus
            // is the whole trick; anything drawn would sit on top of the game.
            setBackgroundColor(0x00000000)
        }

        val params = WindowManager.LayoutParams(
            2, 2,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_TOUCHABLE so every touch goes straight through to the game;
            // NOT_TOUCH_MODAL so it does not swallow what lands outside it.
            // Focusable is the one flag deliberately *not* set here, because
            // leaving it off is what would stop the stick arriving.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        return try {
            wm.addView(target, params)
            manager = wm
            view = target
            target.requestFocus()
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun hide() {
        val wm = manager
        val target = view
        view = null
        manager = null
        if (wm != null && target != null) {
            try {
                wm.removeView(target)
            } catch (e: Throwable) {
                // Already gone.
            }
        }
    }
}
