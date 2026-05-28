package com.odininputmirror

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.io.File

class InputMirrorModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "InputMirror"

    @ReactMethod
    fun startMirror(source: String, target: String, promise: Promise) {
        try {
            val binary = ensureBinaryInstalled()
            val command = "nice -n -20 ${binary.absolutePath.shellQuote()} ${source.shellQuote()} ${target.shellQuote()} >/dev/null 2>&1 &"
            runSu(command)
            reactContext
                .getSharedPreferences(InputMirrorTileService.PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(InputMirrorTileService.KEY_SOURCE, source)
                .putString(InputMirrorTileService.KEY_TARGET, target)
                .apply()
            promise.resolve("started")
        } catch (error: Exception) {
            promise.reject("START_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun stopMirror(promise: Promise) {
        try {
            runSu(STOP_MIRROR_COMMAND)
            promise.resolve("stopped")
        } catch (error: Exception) {
            promise.reject("STOP_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getConnectedDevices(promise: Promise) {
        try {
            val output = runShell("cat /proc/bus/input/devices")
            promise.resolve(parseProcBusInputDevices(output))
        } catch (error: Exception) {
            promise.reject("DEVICE_SCAN_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun isMirrorRunning(promise: Promise) {
        try {
            promise.resolve(isMirrorProcessRunning())
        } catch (_: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun getMirrorStatus(promise: Promise) {
        try {
            val prefs = reactContext.getSharedPreferences(
                InputMirrorTileService.PREFS,
                android.content.Context.MODE_PRIVATE,
            )
            val status: WritableMap = Arguments.createMap()
            status.putBoolean("running", isMirrorProcessRunning())
            status.putString("source", prefs.getString(InputMirrorTileService.KEY_SOURCE, null))
            status.putString("target", prefs.getString(InputMirrorTileService.KEY_TARGET, null))
            promise.resolve(status)
        } catch (error: Exception) {
            promise.reject("STATUS_FAILED", error.message, error)
        }
    }

    private fun ensureBinaryInstalled(): File {
        val binDir = File(reactContext.filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }

        val target = File(binDir, "input_mirror")
        val temp = File(binDir, "input_mirror.tmp")
        reactContext.assets.open("input_mirror/input_mirror").use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (target.exists()) {
            target.delete()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        target.setReadable(true, false)
        target.setExecutable(true, false)
        return target
    }

    private fun parseProcBusInputDevices(output: String): WritableArray {
        val devices = Arguments.createArray()
        val procEntries = output
            .split(Regex("\\n\\s*\\n"))
            .mapNotNull { parseProcInputBlock(it) }
        val controllerEntries = getConnectedGameControllers()
            .mapNotNull { controller -> findProcEntryForController(controller, procEntries) }
            .distinctBy { it.eventName }
            .sortedBy { it.controllerNumber }

        for (entry in controllerEntries) {
            val handlers = Arguments.createArray()
            entry.handlersLine.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .forEach { handlers.pushString(it) }

            val device = Arguments.createMap()
            device.putString("name", entry.name)
            device.putString("path", "/dev/input/${entry.eventName}")
            device.putString("guid", entry.guid)
            device.putInt("controllerNumber", entry.controllerNumber)
            device.putArray("handlers", handlers)
            devices.pushMap(device)
        }

        return devices
    }

    private fun parseProcInputBlock(block: String): ProcInputEntry? {
        val identityMatch = Regex("I: Bus=([0-9A-Fa-f]+) Vendor=([0-9A-Fa-f]+) Product=([0-9A-Fa-f]+)")
            .find(block)
            ?: return null
        val name = Regex("N: Name=\"([^\"]+)\"").find(block)?.groupValues?.getOrNull(1) ?: return null
        val handlersLine = Regex("H: Handlers=(.+)").find(block)?.groupValues?.getOrNull(1) ?: return null
        val eventName = Regex("\\bevent\\d+\\b").find(handlersLine)?.value ?: return null

        return ProcInputEntry(
            block = block,
            name = name,
            handlersLine = handlersLine,
            eventName = eventName,
            bus = identityMatch.groupValues[1].toInt(16),
            vendorId = identityMatch.groupValues[2].toInt(16),
            productId = identityMatch.groupValues[3].toInt(16),
        )
    }

    private fun findProcEntryForController(
        controller: InputDevice,
        entries: List<ProcInputEntry>,
    ): ProcInputEntry? {
        val normalizedName = controller.name.lowercase()
        val candidates = entries.filter { entry ->
            if (!File("/dev/input/${entry.eventName}").exists()) {
                return@filter false
            }

            val sameVendorProduct =
                controller.vendorId != 0 &&
                    controller.productId != 0 &&
                    controller.vendorId != ODIN_VENDOR_ID &&
                    entry.vendorId != ODIN_VENDOR_ID &&
                    entry.vendorId == controller.vendorId &&
                    entry.productId == controller.productId
            val sameName = entry.name.lowercase() == normalizedName
            sameVendorProduct || sameName
        }

        return candidates
            .sortedWith(
                compareByDescending<ProcInputEntry> {
                    controller.vendorId != ODIN_VENDOR_ID &&
                        it.vendorId == controller.vendorId &&
                        it.productId == controller.productId
                }
                    .thenByDescending { it.bus == BUS_BLUETOOTH }
                    .thenByDescending { it.bus == BUS_USB && it.vendorId != ODIN_VENDOR_ID }
                    .thenBy { it.isOdinVirtualController }
                    .thenBy { it.eventNumber }
            )
            .firstOrNull()
            ?.copy(
                guid = controller.getGUID(),
                controllerNumber = controller.controllerNumber,
            )
    }

    private fun isPhysicalGameController(device: InputDevice?): Boolean {
        device ?: return false
        if (device.isVirtual) return false

        val sources = device.sources
        val hasControllerSource =
            sources.hasSource(InputDevice.SOURCE_GAMEPAD) ||
                sources.hasSource(InputDevice.SOURCE_JOYSTICK)
        if (!hasControllerSource) return false

        val hasControllerButtons = device.hasKeys(*CONTROLLER_BUTTONS).any { it }
        val hasControllerAxes = device.motionRanges.any { range ->
            CONTROLLER_AXES.contains(range.axis)
        }
        return hasControllerButtons || hasControllerAxes
    }

    private fun getConnectedGameControllers(): List<InputDevice> {
        return InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> isPhysicalGameController(device) }
            .sortedWith(compareBy<InputDevice> { it.controllerNumber }.thenBy { it.name })
            .toList()
    }

    private fun runSu(command: String): String = runProcess(arrayOf("su", "-c", command))

    private fun runShell(command: String): String = runProcess(arrayOf("sh", "-c", command))

    private fun isMirrorProcessRunning(): Boolean {
        return runCatching {
            runProcess(arrayOf("su", "-c", IS_MIRROR_RUNNING_COMMAND))
            true
        }.getOrDefault(false)
    }

    private fun runProcess(command: Array<String>): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException("Command failed ($exitCode): $output")
        }

        return output
    }
}

private fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

private fun InputDevice.getGUID(): String = String.format("%016x%016x", productId, vendorId)

private fun Int.hasSource(source: Int): Boolean = this and source == source

private data class ProcInputEntry(
    val block: String,
    val name: String,
    val handlersLine: String,
    val eventName: String,
    val bus: Int,
    val vendorId: Int,
    val productId: Int,
    val guid: String = "",
    val controllerNumber: Int = Int.MAX_VALUE,
) {
    val eventNumber: Int
        get() = eventName.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE

    val isBluetoothBus: Boolean
        get() = bus == 0x0005

    val isUsbBus: Boolean
        get() = bus == 0x0003 && vendorId != 0x2020

    val isOdinVirtualController: Boolean
        get() = bus == 0x0003 && vendorId == 0x2020
}

private val CONTROLLER_BUTTONS = intArrayOf(
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_THUMBL,
    KeyEvent.KEYCODE_BUTTON_THUMBR,
    KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

private val CONTROLLER_AXES = intArrayOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RX,
    MotionEvent.AXIS_RY,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_LTRIGGER,
    MotionEvent.AXIS_RTRIGGER,
)

private const val BUS_USB = 0x0003
private const val BUS_BLUETOOTH = 0x0005
private const val ODIN_VENDOR_ID = 0x2020
private const val IS_MIRROR_RUNNING_COMMAND =
    "for pid in \$(pidof input_mirror 2>/dev/null); do state=\$(cat /proc/\$pid/stat 2>/dev/null | awk '{print \$3}'); [ \"\$state\" != \"Z\" ] && exit 0; done; exit 1"
private const val STOP_MIRROR_COMMAND =
    "pids=\$(pidof input_mirror 2>/dev/null); [ -z \"\$pids\" ] && exit 0; kill -TERM \$pids 2>/dev/null; sleep 0.15; for pid in \$pids; do [ -d /proc/\$pid ] && kill -KILL \$pid 2>/dev/null; done; exit 0"
