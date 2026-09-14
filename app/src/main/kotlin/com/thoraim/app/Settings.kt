package com.thoraim.app

import android.content.Context
import java.io.File

/**
 * The tuning values, and the file the daemon reads them out of.
 *
 * Every one of these has to be found by feel against real gameplay — there is
 * no correct deadzone, only the one that stops your thumb drifting. So they are
 * written to a plain file the daemon re-reads on SIGHUP, and changing a slider
 * mid-fight takes effect without dropping the finger that is currently down.
 */
data class Settings(
    val deadzone: Float = 0.12f,
    val curve: Float = 2.0f,
    val maxSpeed: Float = 2.2f,
    val invertY: Boolean = false,
    val regionLeft: Float = 0f,
    val regionTop: Float = 0f,
    val regionRight: Float = 1f,
    val regionBottom: Float = 1f,
    val anchorBias: Float = 0.85f,
    val holdMs: Int = 220,
    val pollHz: Int = 120,
    val toggleButton: Int = BTN_THUMBR,
    val startEnabled: Boolean = true,
) {
    fun toConfigText(): String = buildString {
        appendLine("# written by thoraim; edited live, reloaded on SIGHUP")
        appendLine("deadzone=$deadzone")
        appendLine("curve=$curve")
        appendLine("max_speed=$maxSpeed")
        appendLine("invert_y=${if (invertY) 1 else 0}")
        appendLine("region_left=$regionLeft")
        appendLine("region_top=$regionTop")
        appendLine("region_right=$regionRight")
        appendLine("region_bottom=$regionBottom")
        appendLine("anchor_bias=$anchorBias")
        appendLine("hold_ms=$holdMs")
        appendLine("poll_hz=$pollHz")
        appendLine("toggle_button=$toggleButton")
        appendLine("start_enabled=${if (startEnabled) 1 else 0}")
    }

    companion object {
        const val BTN_THUMBR = 0x13E   // right stick click
        const val BTN_THUMBL = 0x13D
        const val BTN_MODE = 0x13C     // the guide/home button

        /**
         * Where the config lives.
         *
         * Not in the app's own data directory: the daemon runs as root in a
         * completely separate process, and app-private storage under a modern
         * Android is not something an unrelated uid should be reaching into.
         * /data/local/tmp is the shared ground both ends can agree on.
         */
        const val CONFIG_PATH = "/data/local/tmp/thoraim.conf"
        const val LOG_PATH = "/data/local/tmp/thoraim.log"

        private const val PREFS = "thoraim"

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                deadzone = p.getFloat("deadzone", d.deadzone),
                curve = p.getFloat("curve", d.curve),
                maxSpeed = p.getFloat("maxSpeed", d.maxSpeed),
                invertY = p.getBoolean("invertY", d.invertY),
                regionLeft = p.getFloat("regionLeft", d.regionLeft),
                regionTop = p.getFloat("regionTop", d.regionTop),
                regionRight = p.getFloat("regionRight", d.regionRight),
                regionBottom = p.getFloat("regionBottom", d.regionBottom),
                anchorBias = p.getFloat("anchorBias", d.anchorBias),
                holdMs = p.getInt("holdMs", d.holdMs),
                pollHz = p.getInt("pollHz", d.pollHz),
                toggleButton = p.getInt("toggleButton", d.toggleButton),
                startEnabled = p.getBoolean("startEnabled", d.startEnabled),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putFloat("deadzone", deadzone)
            putFloat("curve", curve)
            putFloat("maxSpeed", maxSpeed)
            putBoolean("invertY", invertY)
            putFloat("regionLeft", regionLeft)
            putFloat("regionTop", regionTop)
            putFloat("regionRight", regionRight)
            putFloat("regionBottom", regionBottom)
            putFloat("anchorBias", anchorBias)
            putInt("holdMs", holdMs)
            putInt("pollHz", pollHz)
            putInt("toggleButton", toggleButton)
            putBoolean("startEnabled", startEnabled)
        }.apply()
    }

    /** Stages the config somewhere the app can write, for root to move into place. */
    fun stage(context: Context): File {
        val staged = File(context.cacheDir, "thoraim.conf")
        staged.writeText(toConfigText())
        return staged
    }
}
