package com.thoraim.app

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.app.Activity
import kotlin.math.roundToInt

/**
 * Eight sliders and a start button.
 *
 * Built in code rather than XML, and with no libraries at all, because the APK
 * exists to carry a 16KB binary — anything else in it is weight for a UI that
 * is a column of numbers.
 */
class MainActivity : Activity() {

    private var settings = Settings()
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var travel: TextView
    private lateinit var probe: TextView
    private val overlay by lazy { StickOverlay(this) }

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var watching = false

    /**
     * Whether a self-test is in flight, and what arrived while it was.
     *
     * The app injects a drag onto its own window and watches for it coming back
     * in. That closes the loop: "did anything happen" stops being a judgement
     * call about whether a list twitched, and becomes a fact this process can
     * establish about itself.
     */
    @Volatile private var selfTesting = false
    private val caught = mutableListOf<String>()

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (selfTesting) {
            synchronized(caught) {
                caught.add(
                    "%s at %.0f,%.0f  source=0x%x deviceId=%d toolType=%d".format(
                        android.view.MotionEvent.actionToString(ev.actionMasked),
                        ev.x, ev.y, ev.source, ev.deviceId,
                        ev.getToolType(0),
                    )
                )
            }
            // Swallowed so a synthetic drag cannot press a button under it.
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.load(this)
        setContentView(buildUi())
        refreshStatus()
    }

    override fun onPause() {
        // Nothing to watch while the app is not in front, and a poll left
        // running would keep a Binder call going every 100ms for no one.
        stopWatching()
        super.onPause()
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /**
     * The settings, plus where the game actually is.
     *
     * The service runs in another process and cannot see a window, so the
     * screen it should aim at is measured here — this activity is on the panel
     * you are looking at, which is the panel you want aimed. On a handheld with
     * two of them that is the difference between aiming the game and aiming the
     * other screen.
     */
    /** The settings, plus the screen this app is actually on. */
    private fun current(): Settings {
        val (width, height) = windowBounds()
        return settings.copy(
            screenWidth = width,
            screenHeight = height,
            displayId = currentDisplayId(),
        )
    }

    private fun windowBounds(): Pair<Float, Float> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            null
        }
        return Pair(
            (bounds?.width() ?: resources.displayMetrics.widthPixels).toFloat(),
            (bounds?.height() ?: resources.displayMetrics.heightPixels).toFloat(),
        )
    }

    private fun currentDisplayId(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId ?: 0 else 0

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1013"))
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }

        root.addView(heading("thoraim"))
        root.addView(
            note(
                "Reads the right stick and drags a virtual finger, so a game " +
                    "that only understands a swipe can be aimed with the stick."
            )
        )

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#9BE28B"))
            textSize = 12f
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Start") { startDaemon() })
        buttons.addView(button("Stop") { stopDaemon() })
        buttons.addView(button("Apply") { onApply() })
        buttons.addView(button("Log") { refreshLog() })
        root.addView(buttons)

        val checks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        checks.addView(button("Diagnose") { runDiagnose() })
        checks.addView(button("Test drag") { runTestDrag() })
        checks.addView(button("Self test") { runSelfTest() })
        root.addView(checks)

        probe = TextView(this).apply {
            setTextColor(Color.parseColor("#8FB8D4"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
            text = "Press Watch to see the stick."
        }
        root.addView(probe)
        root.addView(
            button("Watch the stick") { toggleWatching() }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
        )

        root.addView(section("Feel"))

        root.addView(
            slider(
                "Deadzone", settings.deadzone, 0.02f, 0.4f,
                "How far the stick must move before anything happens. Raise it " +
                    "if the aim drifts when you let go."
            ) { settings = settings.copy(deadzone = it); onChanged() }
        )

        root.addView(
            slider(
                "Curve", settings.curve, 1.0f, 4.0f,
                "1 is linear. Higher means a small tilt is much slower than a " +
                    "big one — fine aim and a fast sweep on the same stick."
            ) { settings = settings.copy(curve = it); onChanged() }
        )

        root.addView(
            slider(
                "Top speed", settings.maxSpeed, 0.3f, 8.0f,
                "Screen widths per second at full tilt."
            ) { settings = settings.copy(maxSpeed = it); onChanged() }
        )

        root.addView(
            slider(
                "Hold after centring", settings.holdMs.toFloat(), 0f, 800f,
                "How long the finger stays down once the stick centres. Pausing " +
                    "mid-sweep for less than this does not cost you the stroke."
            ) { settings = settings.copy(holdMs = it.roundToInt()); onChanged() }
        )

        root.addView(section("Travel"))
        root.addView(
            note(
                "A drag has an end: when the finger reaches the edge of its " +
                    "region it must lift and start again, and that hitch is the " +
                    "thing you feel on a long sweep. Both settings below exist " +
                    "to make strokes longer."
            )
        )

        root.addView(
            slider(
                "Region width", settings.regionRight - settings.regionLeft, 0.2f, 1.0f,
                "How much of the screen the finger may use, centred. Leave it at " +
                    "1.00 unless something in the game reacts badly at the edges."
            ) {
                val half = it / 2f
                settings = settings.copy(
                    regionLeft = 0.5f - half,
                    regionRight = 0.5f + half,
                )
                onChanged()
            }
        )

        root.addView(
            slider(
                "Region height", settings.regionBottom - settings.regionTop, 0.2f, 1.0f,
                "The same vertically."
            ) {
                val half = it / 2f
                settings = settings.copy(
                    regionTop = 0.5f - half,
                    regionBottom = 0.5f + half,
                )
                onChanged()
            }
        )

        root.addView(
            slider(
                "Start-from-edge", settings.anchorBias, 0f, 1.0f,
                "0 starts every stroke in the middle of the region, which throws " +
                    "away half the travel before you have moved. 1 starts it hard " +
                    "against the far side, facing the way you are swinging."
            ) { settings = settings.copy(anchorBias = it); onChanged() }
        )

        travel = TextView(this).apply {
            setTextColor(Color.parseColor("#E0B25A"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(travel)

        root.addView(section("Device"))

        root.addView(
            slider(
                "Poll rate", settings.pollHz.toFloat(), 60f, 240f,
                "Ticks per second. Higher is smoother and costs a little battery."
            ) { settings = settings.copy(pollHz = it.roundToInt()); onChanged() }
        )

        root.addView(
            check("Invert Y", settings.invertY) {
                settings = settings.copy(invertY = it); onChanged()
            }
        )

        root.addView(
            check("Aiming on at launch", settings.startEnabled) {
                settings = settings.copy(startEnabled = it); onChanged()
            }
        )

        root.addView(
            note(
                "Click the right stick (R3) to toggle aiming on and off, so " +
                    "menus and normal touch still work."
            )
        )

        log = TextView(this).apply {
            setTextColor(Color.parseColor("#7C8590"))
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(log)

        updateTravel()
        return ScrollView(this).apply { addView(root) }
    }

    // ------------------------------------------------------------------ acts

    private fun service(): AimAccessibilityService? = AimAccessibilityService.instance

    private fun startDaemon() {
        if (!AimAccessibilityService.isEnabled(this)) {
            log.text = SETUP_HELP
            status.text = "Turn thoraim on in Accessibility settings."
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            return
        }
        val running = service()
        if (running == null) {
            status.text = "Accessibility is on but the service has not started " +
                "yet — toggle it off and on."
            return
        }

        settings.save(this)
        // Before Android 14 the stick can only be seen by a focused window, so
        // the overlay has to be up before aiming will do anything at all.
        if (Build.VERSION.SDK_INT < 34) ensureOverlay()

        log.text = running.start(current())
        refreshStatus()
    }

    private fun stopDaemon() {
        service()?.stop()
        overlay.hide()
        refreshStatus()
    }

    private fun onApply() {
        settings.save(this)
        val running = service()
        if (running == null) {
            status.text = "Saved. Not running — press Start."
            return
        }
        running.reconfigure(current())
        status.text = "Applied."
    }

    /**
     * Puts the focus-catching overlay up, asking for the permission if needed.
     *
     * Only ever called below Android 14. From 14 the service is handed motion
     * events directly and there is nothing to put on screen.
     */
    private fun ensureOverlay() {
        if (!StickOverlay.canDraw(this)) {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                )
            )
            return
        }
        overlay.show { event -> service()?.readStick(event) }
    }

    private fun onChanged() {
        settings.save(this)
        updateTravel()
    }

    /**
     * What one stroke actually buys, at the current settings.
     *
     * The same arithmetic the daemon uses, shown while you drag the slider —
     * because "start from the edge" sounds like a detail until you watch the
     * number double.
     */
    private fun updateTravel() {
        val width = settings.regionRight - settings.regionLeft
        val reach = width * (0.5f + 0.5f * settings.anchorBias * 0.96f)
        val hitchesPerSweep = if (reach > 0.001f) 1f / reach else 0f
        travel.text = "One stroke crosses about %.0f%% of the screen — roughly " .format(reach * 100) +
            "%.1f hitches per full sweep across it.".format(hitchesPerSweep)
    }

    private fun refreshStatus() {
        val allowed = AimAccessibilityService.isEnabled(this)
        val running = service()?.isRunning == true
        val needsOverlay = Build.VERSION.SDK_INT < 34 && !StickOverlay.canDraw(this)

        status.text = when {
            !allowed -> "Turn thoraim on in Accessibility settings."
            needsOverlay -> "Also needs 'draw over other apps' on this Android version."
            running -> "Running. R3 toggles aiming."
            else -> "Ready. Not running."
        }
        status.setTextColor(
            when {
                !allowed || needsOverlay -> Color.parseColor("#E0725A")
                running -> Color.parseColor("#9BE28B")
                else -> Color.parseColor("#C9A227")
            }
        )
    }

    /**
     * Polls the service for what the stick is doing.
     *
     * Reading the pad and injecting touch are two separate privileges that fail
     * separately. Without this, a dead aim looks the same whichever one broke —
     * with it, "do these numbers move" answers the question in two seconds.
     */
    private fun toggleWatching() {
        if (watching) {
            stopWatching()
            return
        }
        if (service() == null) {
            probe.text = "Not running — press Start first."
            return
        }
        watching = true
        pollProbe()
    }

    private fun stopWatching() {
        watching = false
        ticker.removeCallbacksAndMessages(null)
    }

    private fun pollProbe() {
        if (!watching) return
        probe.text = service()?.probe() ?: "service went away"
        ticker.postDelayed({ pollProbe() }, 100)
    }

    /**
     * Sends one visible drag, so injection can be judged on its own.
     *
     * If this scrolls a list in any app, injection works and whatever is wrong
     * is the stick, the region or the game. If it does nothing anywhere,
     * injection is the problem and no aiming setting matters yet. Those two
     * cases need completely different fixes, and nothing else tells them apart.
     */
    private fun runTestDrag() {
        val service = service()
        if (service == null) {
            log.text = "Not running — press Start first."
            return
        }
        log.text = "Testing…"
        Thread {
            val bounds = windowBounds()
            val result = try {
                service.testDrag(bounds.first, bounds.second, currentDisplayId())
            } catch (e: Throwable) {
                "test failed: ${e.message}"
            }
            runOnUiThread { log.text = result }
        }.start()
    }

    /**
     * Injects a drag onto this app's own window and reports what came back.
     *
     * The one check that needs no interpretation. If the events arrive here,
     * injection works and everything still wrong is about the game or the
     * stick. If they do not, injection is the problem, and the source and
     * deviceId of whatever *did* arrive says why.
     */
    private fun runSelfTest() {
        val service = service()
        if (service == null) {
            log.text = "Not running — press Start first."
            return
        }
        synchronized(caught) { caught.clear() }
        selfTesting = true
        log.text = "Injecting onto this window…"

        Thread {
            val (width, height) = windowBounds()
            val sent = try {
                service.testDrag(width, height, currentDisplayId())
            } catch (e: Throwable) {
                "test failed: ${e.message}"
            }
            Thread.sleep(400)
            selfTesting = false

            val seen = synchronized(caught) { caught.toList() }
            runOnUiThread {
                log.text = buildString {
                    append(sent).append("\n\n")
                    if (seen.isEmpty()) {
                        append("NOTHING arrived at this app's own window.\n")
                        append("Injection is not reaching the input dispatcher, ")
                        append("so no aiming setting matters yet. Send me the ")
                        append("Diagnose output.")
                    } else {
                        append("${seen.size} events arrived here:\n")
                        append(seen.take(3).joinToString("\n"))
                        if (seen.size > 3) append("\n  …")
                        append("\n\nInjection works. If aiming still does ")
                        append("nothing in NIKKE, the game is either ignoring ")
                        append("synthetic input or is on another display.")
                    }
                }
            }
        }.start()
    }

    private fun runDiagnose() {
        log.text = buildString {
            append("Android ${Build.VERSION.SDK_INT}")
            append(if (Build.VERSION.SDK_INT >= 34) " — motion events available" else " — needs the overlay")
            append("\naccessibility enabled: ${AimAccessibilityService.isEnabled(this@MainActivity)}")
            append("\nservice connected: ${service() != null}")
            append("\noverlay permitted: ${StickOverlay.canDraw(this@MainActivity)}")
            append(", showing: ${overlay.showing}")
            val (w, h) = windowBounds()
            append("\nthis window: ${w.toInt()}x${h.toInt()} on display ${currentDisplayId()}")

            append("\n\npads Android can see:\n")
            var found = 0
            for (id in android.view.InputDevice.getDeviceIds()) {
                val device = android.view.InputDevice.getDevice(id) ?: continue
                val sources = device.sources
                val isPad =
                    sources and android.view.InputDevice.SOURCE_GAMEPAD != 0 ||
                        sources and android.view.InputDevice.SOURCE_JOYSTICK != 0
                if (!isPad) continue
                found++
                append("  \"${device.name}\"\n")
                for (axis in listOf(
                    android.view.MotionEvent.AXIS_RX to "RX",
                    android.view.MotionEvent.AXIS_RY to "RY",
                    android.view.MotionEvent.AXIS_Z to "Z",
                    android.view.MotionEvent.AXIS_RZ to "RZ",
                )) {
                    val range = device.getMotionRange(axis.first)
                    if (range != null) {
                        append("     ${axis.second} ${range.min}..${range.max}\n")
                    }
                }
            }
            if (found == 0) append("  (none — is the pad awake?)\n")

            append("\n")
            append(service()?.probe() ?: "service not connected")
        }
    }

    private fun refreshLog() {
        log.text = service()?.report ?: SETUP_HELP
    }

    // ----------------------------------------------------------------- views

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 22f
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(Color.parseColor("#5E6A75"))
        textSize = 11f
        letterSpacing = 0.14f
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#7C8590"))
        textSize = 12f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun check(text: String, value: Boolean, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            this.text = text
            isChecked = value
            setTextColor(Color.parseColor("#D7DEE5"))
            textSize = 13f
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    /**
     * A labelled slider over a float range.
     *
     * SeekBar is integers only, so the range is stepped into 1000 and converted
     * on the way in and out — a deadzone that could only be set in whole units
     * would be no setting at all.
     */
    private fun slider(
        label: String,
        value: Float,
        min: Float,
        max: Float,
        help: String,
        onChange: (Float) -> Unit,
    ): View {
        val steps = 1000
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        val readout = TextView(this).apply {
            setTextColor(Color.parseColor("#D7DEE5"))
            textSize = 13f
            text = "$label   ${format(value)}"
        }
        box.addView(readout)

        box.addView(SeekBar(this).apply {
            this.max = steps
            progress = (((value - min) / (max - min)) * steps)
                .coerceIn(0f, steps.toFloat()).roundToInt()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * (p.toFloat() / steps)
                    readout.text = "$label   ${format(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                // Only pushed on release: reconfiguring per pixel of slider
                // travel would rebuild the aim state hundreds of times a second.
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    AimAccessibilityService.instance?.reconfigure(
                        this@MainActivity.current()
                    )
                }
            })
        })

        box.addView(TextView(this).apply {
            text = help
            setTextColor(Color.parseColor("#6B747E"))
            textSize = 11f
        })

        return box
    }

    private fun format(v: Float): String =
        if (v >= 20f) v.roundToInt().toString() else "%.2f".format(v)

    private companion object {
        const val SETUP_HELP =
            "thoraim needs Accessibility, and on Android 13 and below also " +
                "'draw over other apps'.\n\n" +
                "1. Settings > Accessibility > thoraim > on.\n" +
                "2. If asked, allow drawing over other apps.\n" +
                "3. Come back and press Start.\n\n" +
                "Accessibility is what allows dispatchGesture — the only way " +
                "an ordinary app puts touch into another app without root. " +
                "The overlay is only needed before Android 14, where a focused " +
                "window is the one thing that can see a gamepad stick."
    }
}
