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
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
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
    private lateinit var client: AimClient

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var watching = false

    private val onPermission =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    log.text = "Allowed. Press Start."
                }
                refreshStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.load(this)
        client = AimClient(this)
        setContentView(buildUi())
        try {
            Shizuku.addRequestPermissionResultListener(onPermission)
        } catch (e: Throwable) {
            // Shizuku is not installed; the status line will say so.
        }
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
        try {
            Shizuku.removeRequestPermissionResultListener(onPermission)
        } catch (e: Throwable) {
            // Nothing bound.
        }
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
    private fun outgoing(): String {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            null
        }
        val width = bounds?.width() ?: resources.displayMetrics.widthPixels
        val height = bounds?.height() ?: resources.displayMetrics.heightPixels
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: 0
        } else {
            0
        }
        return settings.copy(
            screenWidth = width.toFloat(),
            screenHeight = height.toFloat(),
            displayId = display,
        ).toConfigText()
    }

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

    private fun startDaemon() {
        when (Privilege.state()) {
            Privilege.State.Unavailable -> {
                status.text = Privilege.describe()
                log.text = SETUP_HELP
                return
            }
            Privilege.State.NeedsPermission -> {
                Privilege.requestPermission()
                return
            }
            Privilege.State.Ready -> Unit
        }

        settings.save(this)
        client.connect { service ->
            runOnUiThread {
                if (service == null) {
                    status.text = "Could not start the service through Shizuku."
                    return@runOnUiThread
                }
                log.text = try {
                    service.start(outgoing())
                } catch (e: Throwable) {
                    "start failed: ${e.message}"
                }
                refreshStatus()
            }
        }
    }

    private fun stopDaemon() {
        try {
            client.get()?.stop()
        } catch (e: Throwable) {
            // Already gone.
        }
        refreshStatus()
    }

    private fun onApply() {
        settings.save(this)
        val service = client.get()
        if (service == null) {
            status.text = "Saved. Not running — press Start."
            return
        }
        try {
            service.reconfigure(outgoing())
            status.text = "Applied to the running service."
        } catch (e: Throwable) {
            status.text = "Could not apply: ${e.message}"
        }
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
        val state = Privilege.state()
        val running = try {
            client.get()?.isRunning == true
        } catch (e: Throwable) {
            false
        }

        status.text = when {
            state == Privilege.State.Unavailable -> Privilege.describe()
            state == Privilege.State.NeedsPermission -> "Shizuku is running. Press Start to allow thoraim."
            running -> "Running. R3 toggles aiming."
            else -> "Shizuku ready. Not running."
        }
        status.setTextColor(
            when {
                state == Privilege.State.Unavailable -> Color.parseColor("#E0725A")
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
        if (client.get() == null) {
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
        probe.text = try {
            client.get()?.probe() ?: "service went away"
        } catch (e: Throwable) {
            "probe failed: ${e.message}"
        }
        ticker.postDelayed({ pollProbe() }, 100)
    }

    private fun refreshLog() {
        log.text = try {
            client.get()?.status() ?: SETUP_HELP
        } catch (e: Throwable) {
            "status failed: ${e.message}"
        }
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
                // Only pushed on release: a reconfigure per pixel of slider
                // travel would be a few hundred Binder calls a second.
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    try {
                        this@MainActivity.client.get()?.reconfigure(outgoing())
                    } catch (e: Throwable) {
                        // The service is not up; Start will send it anyway.
                    }
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
            "thoraim needs Shizuku, which grants shell privileges without root.\n\n" +
                "1. Install Shizuku from the Play Store or GitHub.\n" +
                "2. Start it with wireless debugging (Shizuku walks you through " +
                "it) — this has to be redone after a reboot.\n" +
                "3. Come back and press Start, then allow thoraim.\n\n" +
                "Shell is in the `input` group and holds INJECT_EVENTS, which " +
                "is why it can read the stick and inject touch while a game is " +
                "in front. An ordinary app uid can do neither."
    }
}
