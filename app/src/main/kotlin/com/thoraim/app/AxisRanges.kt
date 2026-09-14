package com.thoraim.app

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Axis ranges, taken from Android rather than guessed.
 *
 * `EVIOCGABS` is the natural source and is an ioctl, which Java cannot issue.
 * Android reports the same numbers through `InputDevice.getMotionRange`, from
 * any process, with no focus required — focus governs event *delivery*, not
 * what you can ask about a device.
 *
 * Matching a kernel node to an Android device is done by name, which is the one
 * field both sides agree on: `InputDevice.getName()` is the string the driver
 * put in `EVIOCGNAME`, and that is what `/proc/bus/input/devices` prints.
 */
object AxisRanges {

    /** Evdev ABS codes to the Android axis constants that mirror them. */
    private val toAndroidAxis = mapOf(
        Evdev.ABS_X to MotionEvent.AXIS_X,
        Evdev.ABS_Y to MotionEvent.AXIS_Y,
        Evdev.ABS_Z to MotionEvent.AXIS_Z,
        Evdev.ABS_RX to MotionEvent.AXIS_RX,
        Evdev.ABS_RY to MotionEvent.AXIS_RY,
        Evdev.ABS_RZ to MotionEvent.AXIS_RZ,
    )

    /**
     * Fills [x] and [y] for the pad called [deviceName], if Android knows it.
     *
     * Returns whether both were found. When it fails the ranges keep their
     * assumed values and say so, which is worth surfacing: on a pad that
     * reports a narrow range, an assumption is the difference between aiming
     * and a stick that appears dead.
     */
    fun read(deviceName: String, codeX: Int, codeY: Int, x: AxisRange, y: AxisRange): Boolean {
        val device = InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { it.name == deviceName }
            ?: return false

        return apply(device, codeX, x) && apply(device, codeY, y)
    }

    private fun apply(device: InputDevice, code: Int, into: AxisRange): Boolean {
        val axis = toAndroidAxis[code] ?: return false
        // Android reports ranges as floats because it has already normalised
        // some devices; the raw kernel values are what evdev will hand us, and
        // for these axes the two are the same numbers.
        val range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: device.getMotionRange(axis)
            ?: return false
        into.set(range.min.toInt(), range.max.toInt())
        return into.known
    }
}
