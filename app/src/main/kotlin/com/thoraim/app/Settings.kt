package com.thoraim.app

import android.content.Context

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
    // Filled in by the app from the window it is running in, so the service
    // does not have to guess which of two screens the game is on.
    val screenWidth: Float = 1080f,
    val screenHeight: Float = 1920f,
    val displayId: Int = 0,
    /** TOUCH, MOUSE or STYLUS — which pointer the injected events claim to be. */
    val injectMode: String = "TOUCH",
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
        appendLine("screen_width=$screenWidth")
        appendLine("screen_height=$screenHeight")
        appendLine("display_id=$displayId")
        appendLine("inject_mode=$injectMode")
    }

    companion object {
        const val BTN_THUMBR = 0x13E   // right stick click
        const val BTN_THUMBL = 0x13D
        const val BTN_MODE = 0x13C     // the guide/home button

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
                injectMode = p.getString("injectMode", d.injectMode) ?: d.injectMode,
            )
        }

        /**
         * Reads settings back from the text form.
         *
         * The service runs in a different process as a different uid, so the
         * settings have to survive a trip across a Binder as something simple.
         * Text, so the same string can be logged and eyeballed when a value
         * turns out not to be what the slider said.
         */
        fun parse(text: String): Settings {
            val values = mutableMapOf<String, String>()
            for (line in text.lines()) {
                val clean = line.substringBefore('#').trim()
                val key = clean.substringBefore('=', "").trim()
                if (key.isEmpty()) continue
                values[key] = clean.substringAfter('=').trim()
            }

            fun f(key: String, fallback: Float) =
                values[key]?.toFloatOrNull() ?: fallback
            fun i(key: String, fallback: Int) =
                values[key]?.toFloatOrNull()?.toInt() ?: fallback
            fun b(key: String, fallback: Boolean) =
                values[key]?.let { it == "1" || it == "true" } ?: fallback

            val d = Settings()
            // Clamped on the way in rather than trusted: these cross a process
            // boundary, and a zero poll rate or an inside-out region would be a
            // hang or a divide by zero rather than a bad setting.
            var left = f("region_left", d.regionLeft).coerceIn(0f, 1f)
            var right = f("region_right", d.regionRight).coerceIn(0f, 1f)
            var top = f("region_top", d.regionTop).coerceIn(0f, 1f)
            var bottom = f("region_bottom", d.regionBottom).coerceIn(0f, 1f)
            if (right - left < 0.05f) { left = 0f; right = 1f }
            if (bottom - top < 0.05f) { top = 0f; bottom = 1f }

            return Settings(
                deadzone = f("deadzone", d.deadzone).coerceIn(0.01f, 0.9f),
                curve = f("curve", d.curve).coerceIn(0.2f, 6f),
                maxSpeed = f("max_speed", d.maxSpeed).coerceIn(0.05f, 20f),
                invertY = b("invert_y", d.invertY),
                regionLeft = left,
                regionTop = top,
                regionRight = right,
                regionBottom = bottom,
                anchorBias = f("anchor_bias", d.anchorBias).coerceIn(0f, 1f),
                holdMs = i("hold_ms", d.holdMs).coerceIn(0, 5000),
                pollHz = i("poll_hz", d.pollHz).coerceIn(30, 250),
                toggleButton = i("toggle_button", d.toggleButton),
                startEnabled = b("start_enabled", d.startEnabled),
                screenWidth = f("screen_width", d.screenWidth).coerceAtLeast(1f),
                screenHeight = f("screen_height", d.screenHeight).coerceAtLeast(1f),
                displayId = i("display_id", d.displayId),
                injectMode = values["inject_mode"] ?: d.injectMode,
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
            putString("injectMode", injectMode)
        }.apply()
    }

}
