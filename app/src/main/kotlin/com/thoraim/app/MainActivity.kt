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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.load(this)
        setContentView(buildUi())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
        if (!Root.available()) {
            status.text = "No root. /dev/uinput cannot be opened without it, " +
                "and there is no way round that."
            return
        }
        settings.save(this)
        val result = Root.start(
            applicationInfo.nativeLibraryDir,
            settings.stage(this),
            preferredTouchWidth(),
        )
        log.text = result.output.trim()
        refreshStatus()
    }

    private fun stopDaemon() {
        Root.stop()
        refreshStatus()
    }

    private fun onApply() {
        settings.save(this)
        if (!Root.isRunning()) {
            status.text = "Saved. Not running — press Start."
            return
        }
        Root.reload(settings.stage(this))
        status.text = "Applied to the running daemon."
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

    /**
     * Which screen to aim at, on a handheld that has two.
     *
     * The game is on one of them and the daemon has to pick the same one. The
     * window's own width is the honest answer: this app is on the screen you
     * are looking at, which is the screen you want aimed.
     */
    private fun preferredTouchWidth(): Int {
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            null
        }
        return metrics?.width() ?: resources.displayMetrics.widthPixels
    }

    private fun refreshStatus() {
        val root = Root.available()
        val running = root && Root.isRunning()
        status.text = when {
            !root -> "No root — thoraim cannot run."
            running -> "Running. R3 toggles aiming."
            else -> "Root OK. Not running."
        }
        status.setTextColor(
            when {
                !root -> Color.parseColor("#E0725A")
                running -> Color.parseColor("#9BE28B")
                else -> Color.parseColor("#C9A227")
            }
        )
    }

    private fun refreshLog() {
        log.text = Root.log().trim().ifEmpty { "(nothing logged yet)" }
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
                // Only pushed to the daemon on release: a reload per pixel of
                // slider travel would be a few hundred root calls a second.
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (Root.isRunning()) Root.reload(settings.stage(this@MainActivity))
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
}
