package com.thoraim.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Getting hold of shell's privileges, without root.
 *
 * Two things are needed and neither is available to an ordinary app uid:
 * reading `/dev/input/event*`, which is gated on the `input` group, and calling
 * `injectInputEvent`, which is gated on the signature permission INJECT_EVENTS.
 * Shell has both — that is why `adb shell getevent` and `adb shell input tap`
 * work — and Shizuku is what runs a service of ours as shell.
 *
 * None of this is about focus. Focus decides which *window* Android delivers
 * events to; it has no bearing on what a process may read out of /dev or which
 * system calls it may make.
 */
object Privilege {

    const val REQUEST_CODE = 5119

    enum class State {
        /** Shizuku is not installed, or has not been started since boot. */
        Unavailable,
        /** Running, but this app has not been allowed to use it yet. */
        NeedsPermission,
        Ready,
    }

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.Unavailable
            Shizuku.isPreV11() -> State.Unavailable
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.Ready
            else -> State.NeedsPermission
        }
    } catch (e: Throwable) {
        State.Unavailable
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            // Nothing useful to do: the state check will keep saying it is not
            // ready, which is the honest answer.
        }
    }

    /** Whether the version of Shizuku running is one this can talk to. */
    fun describe(): String = try {
        if (!Shizuku.pingBinder()) {
            "Shizuku is not running. Start it, then come back."
        } else {
            "Shizuku ${Shizuku.getVersion()}, uid ${Shizuku.getUid()}"
        }
    } catch (e: Throwable) {
        "Shizuku is not installed."
    }
}

/**
 * The connection to our own code running as shell.
 *
 * The service lives in a separate process with a different uid, so everything
 * across this boundary is plain strings — settings go over as the same text the
 * app would have written to a file, which means what the service received can
 * be logged and read back when a value turns out not to be what the slider said.
 */
class AimClient(private val context: Context) {

    private var service: IAimService? = null
    private var onReady: ((IAimService?) -> Unit)? = null

    val connected: Boolean get() = service != null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, AimService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("aim")
        .debuggable(false)
        .version(BuildConfig.SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) {
                IAimService.Stub.asInterface(binder)
            } else {
                null
            }
            onReady?.invoke(service)
            onReady = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun connect(then: (IAimService?) -> Unit) {
        val existing = service
        if (existing != null) {
            then(existing)
            return
        }
        onReady = then
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            onReady = null
            then(null)
        }
    }

    fun disconnect() {
        try {
            Shizuku.unbindUserService(args, connection, true)
        } catch (e: Throwable) {
            // Already gone.
        }
        service = null
    }

    fun get(): IAimService? = service
}
