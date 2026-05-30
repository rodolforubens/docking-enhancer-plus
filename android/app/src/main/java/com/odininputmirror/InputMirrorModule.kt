package com.odininputmirror

import android.content.Context
import android.content.Intent
import android.os.Build
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
    fun startMirror(
        source: String,
        target: String,
        homeAsBack: Boolean,
        comboHoldKillApp: Boolean,
        sourceGuid: String?,
        targetGuid: String?,
        promise: Promise,
    ) {
        try {
            val binary = ensureBinaryInstalled()
            val pidFile = getMirrorPidFile()
            val heartbeatFile = getMirrorHeartbeatFile()
            pidFile.writeText("")
            pidFile.setReadable(true, false)
            pidFile.setWritable(true, false)
            heartbeatFile.writeText("")
            heartbeatFile.setReadable(true, false)
            heartbeatFile.setWritable(true, false)
            val homeAsBackArg = if (homeAsBack) " --home-as-back" else ""
            val comboHoldKillAppArg = if (comboHoldKillApp) " --combo-hold-kill-app" else ""
            val pidFileArg = " --pid-file ${pidFile.absolutePath.shellQuote()}"
            val heartbeatFileArg = " --heartbeat-file ${heartbeatFile.absolutePath.shellQuote()}"
            val command = "nice -n -20 ${binary.absolutePath.shellQuote()} ${source.shellQuote()} ${target.shellQuote()}$homeAsBackArg$comboHoldKillAppArg$pidFileArg$heartbeatFileArg >/dev/null 2>&1 & echo \$! > ${pidFile.absolutePath.shellQuote()}; chmod 666 ${pidFile.absolutePath.shellQuote()} ${heartbeatFile.absolutePath.shellQuote()}"
            runSu(command)
            reactContext
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SOURCE, source)
                .putString(KEY_TARGET, target)
                .putString(KEY_SOURCE_GUID, sourceGuid)
                .putString(KEY_TARGET_GUID, targetGuid)
                .putBoolean(KEY_HOME_AS_BACK, homeAsBack)
                .putBoolean(KEY_COMBO_HOLD_KILL_APP, comboHoldKillApp)
                .putBoolean(KEY_EXPECTED_RUNNING, true)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .apply()
            startSupervisorIfNeeded()
            promise.resolve("started")
        } catch (error: Exception) {
            promise.reject("START_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun stopMirror(promise: Promise) {
        try {
            runSu(STOP_MIRROR_COMMAND)
            getMirrorPidFile().delete()
            getMirrorHeartbeatFile().delete()
            reactContext
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EXPECTED_RUNNING, false)
                .apply()
            stopSupervisor()
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
            promise.resolve(buildMirrorStatus(isMirrorProcessRunning()))
        } catch (error: Exception) {
            promise.reject("STATUS_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getMirrorStatusVerified(promise: Promise) {
        try {
            val running = isMirrorProcessRunningWithRoot()
            if (!running) {
                getMirrorPidFile().delete()
            }
            promise.resolve(buildMirrorStatus(running))
        } catch (error: Exception) {
            promise.reject("STATUS_VERIFY_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun setHomeAsBackEnabled(enabled: Boolean, promise: Promise) {
        try {
            reactContext
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HOME_AS_BACK, enabled)
                .apply()
            promise.resolve(enabled)
        } catch (error: Exception) {
            promise.reject("SAVE_SETTING_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun setComboHoldKillAppEnabled(enabled: Boolean, promise: Promise) {
        try {
            reactContext
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMBO_HOLD_KILL_APP, enabled)
                .apply()
            promise.resolve(enabled)
        } catch (error: Exception) {
            promise.reject("SAVE_SETTING_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun setAutoRestartEnabled(enabled: Boolean, promise: Promise) {
        try {
            reactContext
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_RESTART, enabled)
                .apply()
            val prefs = reactContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (enabled && prefs.getBoolean(KEY_EXPECTED_RUNNING, false)) {
                startSupervisor()
            } else {
                stopSupervisor()
            }
            promise.resolve(enabled)
        } catch (error: Exception) {
            promise.reject("SAVE_SETTING_FAILED", error.message, error)
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
        val prefs = reactContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val expectedRunning = prefs.getBoolean(KEY_EXPECTED_RUNNING, false)
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val startingGrace = expectedRunning && System.currentTimeMillis() - startedAt < STARTING_GRACE_MS
        if (isHeartbeatFresh()) {
            return true
        }
        if (!startingGrace) {
            return false
        }

        val pidFile = getMirrorPidFile()
        val pid = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull()
            ?: return startingGrace
        val procDir = File("/proc/$pid")
        if (!procDir.exists()) {
            return startingGrace
        }

        val state = readProcessState(pid)
        if (state == "Z") {
            pidFile.delete()
            prefs.edit().putBoolean(KEY_EXPECTED_RUNNING, false).apply()
            return false
        }

        return true
    }

    private fun isHeartbeatFresh(): Boolean {
        val heartbeatFile = getMirrorHeartbeatFile()
        if (!heartbeatFile.exists()) {
            return false
        }

        return System.currentTimeMillis() - heartbeatFile.lastModified() <= HEARTBEAT_STALE_MS
    }

    private fun isMirrorProcessRunningWithRoot(): Boolean {
        return runCatching {
            runProcess(arrayOf("su", "-c", IS_MIRROR_RUNNING_COMMAND))
            true
        }.getOrDefault(false)
    }

    private fun buildMirrorStatus(running: Boolean): WritableMap {
        val prefs = reactContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val status: WritableMap = Arguments.createMap()
        status.putBoolean("running", running)
        status.putBoolean("expectedRunning", prefs.getBoolean(KEY_EXPECTED_RUNNING, false))
        status.putString("source", prefs.getString(KEY_SOURCE, null))
        status.putString("target", prefs.getString(KEY_TARGET, null))
        status.putString("sourceGuid", prefs.getString(KEY_SOURCE_GUID, null))
        status.putString("targetGuid", prefs.getString(KEY_TARGET_GUID, null))
        status.putBoolean("homeAsBack", prefs.getBoolean(KEY_HOME_AS_BACK, false))
        status.putBoolean("comboHoldKillApp", prefs.getBoolean(KEY_COMBO_HOLD_KILL_APP, false))
        status.putBoolean("autoRestart", prefs.getBoolean(KEY_AUTO_RESTART, false))
        return status
    }

    private fun getMirrorPidFile(): File = File(reactContext.filesDir, "input_mirror.pid")

    private fun getMirrorHeartbeatFile(): File = File(reactContext.filesDir, "input_mirror.heartbeat")

    private fun startSupervisorIfNeeded() {
        val prefs = reactContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO_RESTART, false)) {
            startSupervisor()
        }
    }

    private fun startSupervisor() {
        val intent = Intent(reactContext, InputMirrorSupervisorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    private fun stopSupervisor() {
        reactContext.stopService(Intent(reactContext, InputMirrorSupervisorService::class.java))
    }

    private fun readProcessState(pid: Int): String? {
        return runCatching {
            val stat = File("/proc/$pid/stat").readText()
            val stateStart = stat.lastIndexOf(") ") + 2
            stat.substring(stateStart).trim().split(Regex("\\s+")).firstOrNull()
        }.getOrNull()
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

fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

fun InputDevice.getGUID(): String = String.format("%016x%016x", productId, vendorId)

fun Int.hasSource(source: Int): Boolean = this and source == source

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

val CONTROLLER_BUTTONS = intArrayOf(
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

val CONTROLLER_AXES = intArrayOf(
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

const val BUS_USB = 0x0003
const val BUS_BLUETOOTH = 0x0005
const val ODIN_VENDOR_ID = 0x2020
const val PREFS = "input_mirror"
const val KEY_SOURCE = "source"
const val KEY_TARGET = "target"
const val KEY_SOURCE_GUID = "source_guid"
const val KEY_TARGET_GUID = "target_guid"
const val KEY_HOME_AS_BACK = "home_as_back"
const val KEY_COMBO_HOLD_KILL_APP = "combo_hold_kill_app"
const val KEY_AUTO_RESTART = "auto_restart"
const val KEY_EXPECTED_RUNNING = "expected_running"
const val KEY_STARTED_AT = "started_at"
const val STARTING_GRACE_MS = 1500L
const val HEARTBEAT_STALE_MS = 5000L
const val IS_MIRROR_RUNNING_COMMAND =
    "for pid in \$(pidof input_mirror 2>/dev/null); do state=\$(cat /proc/\$pid/stat 2>/dev/null | awk '{print \$3}'); [ \"\$state\" != \"Z\" ] && exit 0; done; exit 1"
const val STOP_MIRROR_COMMAND =
    "pids=\$(pidof input_mirror 2>/dev/null); [ -z \"\$pids\" ] && exit 0; kill -TERM \$pids 2>/dev/null; sleep 0.15; for pid in \$pids; do [ -d /proc/\$pid ] && kill -KILL \$pid 2>/dev/null; done; exit 0"
