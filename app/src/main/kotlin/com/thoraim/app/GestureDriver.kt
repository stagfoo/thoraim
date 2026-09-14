package com.thoraim.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * A finger, made out of accessibility gestures.
 *
 * `dispatchGesture` is the only way to put touch into another app without root,
 * and it is nothing like injecting an event: you describe a stroke with a
 * *duration*, and it plays out over that time. A continuous drag is therefore a
 * chain — each segment declares `willContinue`, and the next one is started from
 * the previous one's end when it reports finished.
 *
 * That chaining is also the pacing. There is no point asking where the stick is
 * more often than a segment completes, so the aim loop writes a target here and
 * this walks toward it at whatever rate the system actually manages. A fixed
 * tick would only queue work that could not be delivered.
 */
class GestureDriver(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())

    /** Where the finger should be heading, or null to let go. */
    @Volatile private var target: Pair<Float, Float>? = null

    /** Where it currently is, as far as the last dispatched stroke got. */
    private var at: Pair<Float, Float> = 0f to 0f

    @Volatile private var inFlight = false
    @Volatile var down = false
        private set

    @Volatile var displayId: Int = 0

    /**
     * How long each segment lasts.
     *
     * Short enough that aim keeps up, long enough that the system can actually
     * deliver it — below about a frame the gestures start being dropped rather
     * than played, which reads as the aim sticking.
     */
    @Volatile var segmentMs: Long = 20

    @Volatile var lastError: String? = null
    @Volatile var dispatched: Long = 0
    @Volatile var completed: Long = 0
    @Volatile var cancelled: Long = 0

    /** Aim wants the finger here. Starts a stroke if none is running. */
    fun moveTo(x: Float, y: Float) {
        target = x to y
        if (!inFlight) main.post { pump() }
    }

    /** Aim is done. The chain ends at its next opportunity. */
    fun release() {
        target = null
        if (!inFlight) main.post { pump() }
    }

    /** Drops everything immediately, for when aiming is toggled off. */
    fun abandon() {
        target = null
        main.post {
            service.dispatchGesture(
                // A one-pixel stroke that does not continue is the only way to
                // end a chain early; there is no cancel.
                strokeFor(at, at, continues = false) ?: return@post,
                null,
                null,
            )
            down = false
            inFlight = false
        }
    }

    private fun pump() {
        if (inFlight) return
        val want = target

        if (!down) {
            if (want == null) return
            at = want
            dispatch(at, at, continues = true, press = true)
            return
        }

        if (want == null) {
            dispatch(at, at, continues = false, press = false)
            return
        }

        val from = at
        at = want
        dispatch(from, want, continues = true, press = false)
    }

    private fun dispatch(
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
        continues: Boolean,
        press: Boolean,
    ) {
        val stroke = strokeFor(from, to, continues) ?: return
        inFlight = true
        dispatched++

        val ok = try {
            service.dispatchGesture(
                stroke,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed++
                        inFlight = false
                        if (!continues) down = false
                        pump()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        // Usually the system taking the gesture away — another
                        // service, or the screen changing under it. Dropping the
                        // chain and starting a new one beats wedging.
                        cancelled++
                        inFlight = false
                        down = false
                        pump()
                    }
                },
                main,
            )
        } catch (e: Throwable) {
            lastError = "dispatch: ${e.message}"
            inFlight = false
            false
        }

        if (!ok) {
            lastError = lastError ?: "dispatchGesture refused the stroke"
            inFlight = false
            down = false
            return
        }
        if (press) down = true
    }

    private fun strokeFor(
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
        continues: Boolean,
    ): GestureDescription? {
        val path = Path().apply {
            moveTo(from.first, from.second)
            // A stroke whose start and end are identical is rejected as empty,
            // so a press or a lift in place needs a nudge of a fraction of a
            // pixel — far too small to move anything, large enough to exist.
            if (from == to) {
                lineTo(to.first + 0.1f, to.second)
            } else {
                lineTo(to.first, to.second)
            }
        }

        return try {
            // Always through the chain: whether this is a press or a
            // continuation is decided by whether one is already open, not by a
            // flag that could disagree with it. Starting a fresh stroke while
            // the system still holds an open one is what makes the finger
            // appear to lift and stab.
            val stroke = continuationOf(path, continues)
            GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Public API, unlike MotionEvent.setDisplayId — on a
                    // two-screen handheld this is the whole ballgame.
                    setDisplayId(displayId)
                }
            }.build()
        } catch (e: Throwable) {
            lastError = "stroke: ${e.message}"
            null
        }
    }

    private var previous: GestureDescription.StrokeDescription? = null

    private fun continuationOf(
        path: Path,
        continues: Boolean,
    ): GestureDescription.StrokeDescription {
        val last = previous
        val stroke = if (last != null) {
            last.continueStroke(path, 0, segmentMs, continues)
        } else {
            GestureDescription.StrokeDescription(path, 0, segmentMs, continues)
        }
        previous = if (continues) stroke else null
        return stroke
    }

    fun report(): String = buildString {
        append("gestures dispatched $dispatched, completed $completed")
        if (cancelled > 0) append(", cancelled $cancelled")
        append("\nfinger ${if (down) "down" else "up"}")
        append(", segment ${segmentMs}ms, display $displayId")
        lastError?.let { append("\nlast error: $it") }
    }
}
