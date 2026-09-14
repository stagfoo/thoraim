package com.thoraim.app

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Talking to su.
 *
 * /dev/uinput is root-only on essentially every Android device — shell cannot
 * open it even over ADB — so there is no unrooted path to a virtual touchscreen
 * and no point pretending otherwise. What this does not do is hold a root shell
 * open: each call is its own short-lived one, so a crash here cannot leave a
 * root process parented to the UI.
 */
object Root {

    data class Result(val ok: Boolean, val output: String)

    fun available(): Boolean = run("id -u").output.trim() == "0"

    fun run(command: String, timeoutMs: Long = 8000): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val text = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val reaper = Thread {
                reader.useLines { lines -> lines.forEach { text.appendLine(it) } }
            }
            reaper.start()
            if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Result(false, "timed out\n$text")
            }
            reaper.join(500)
            Result(process.exitValue() == 0, text.toString())
        } catch (e: Exception) {
            Result(false, e.message ?: "no su")
        }
    }

    /** The daemon's path inside the app, which is the one place it may be run from. */
    fun daemonPath(nativeLibraryDir: String): String =
        File(nativeLibraryDir, "libthoraim.so").absolutePath

    fun isRunning(): Boolean =
        run("pgrep -f libthoraim.so").output.trim().isNotEmpty()

    fun start(nativeLibraryDir: String, stagedConfig: File, preferTouchWidth: Int): Result {
        val daemon = daemonPath(nativeLibraryDir)
        // Copied rather than read in place: the staged file sits in the app's
        // cache, which root can read now but which the system may clear under
        // it at any point, taking the running daemon's config with it.
        val command = buildString {
            append("cp '${stagedConfig.absolutePath}' ${Settings.CONFIG_PATH}; ")
            append("chmod 644 ${Settings.CONFIG_PATH}; ")
            append("pkill -f libthoraim.so; ")
            append("nohup $daemon ${Settings.CONFIG_PATH} $preferTouchWidth ")
            append("> ${Settings.LOG_PATH} 2>&1 &")
            append(" sleep 1; cat ${Settings.LOG_PATH}")
        }
        return run(command, timeoutMs = 12000)
    }

    fun stop(): Result = run("pkill -f libthoraim.so")

    /** Pushes changed settings to a daemon that is already running. */
    fun reload(stagedConfig: File): Result = run(
        "cp '${stagedConfig.absolutePath}' ${Settings.CONFIG_PATH}; " +
            "chmod 644 ${Settings.CONFIG_PATH}; " +
            "pkill -HUP -f libthoraim.so"
    )

    fun log(): String = run("tail -n 40 ${Settings.LOG_PATH}").output
}
