package com.odininputmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File

class InputMirrorSupervisorService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = Thread({ superviseMirror() }, "input-mirror-supervisor").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun superviseMirror() {
        var nextRestartAllowedAt = 0L
        var sleepMs = SUPERVISOR_INTERVAL_MS

        while (running) {
            try {
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val autoRestart = prefs.getBoolean(KEY_AUTO_RESTART, false)
                val expectedRunning = prefs.getBoolean(KEY_EXPECTED_RUNNING, false)

                if (!autoRestart) {
                    stopSelf()
                    break
                }

                if (!expectedRunning) {
                    sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                } else if (isMirrorProcessRunning()) {
                    sleepMs = SUPERVISOR_INTERVAL_MS
                } else {
                    getMirrorPidFile().delete()
                    val devices = getConnectedControllerEntries()
                    val source = findSavedDevice(
                        path = prefs.getString(KEY_SOURCE, null),
                        guid = prefs.getString(KEY_SOURCE_GUID, null),
                        devices = devices,
                    )
                    val target = findSavedDevice(
                        path = prefs.getString(KEY_TARGET, null),
                        guid = prefs.getString(KEY_TARGET_GUID, null),
                        devices = devices,
                    )

                    if (source != null && target != null && source.path != target.path) {
                        val now = System.currentTimeMillis()
                        if (now >= nextRestartAllowedAt) {
                            startMirrorProcess(
                                source = source,
                                target = target,
                                homeAsBack = prefs.getBoolean(KEY_HOME_AS_BACK, false),
                                comboHoldKillApp = prefs.getBoolean(KEY_COMBO_HOLD_KILL_APP, false),
                            )
                            prefs.edit()
                                .putString(KEY_SOURCE, source.path)
                                .putString(KEY_TARGET, target.path)
                                .putString(KEY_SOURCE_GUID, source.guid)
                                .putString(KEY_TARGET_GUID, target.guid)
                                .apply()
                            nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                            sleepMs = SUPERVISOR_INTERVAL_MS
                        } else {
                            sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                        }
                    } else {
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                }
            } catch (_: Exception) {
                // Keep the supervisor alive; transient root/device failures are expected during reconnects.
                sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
            }

            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun startMirrorProcess(
        source: ControllerEntry,
        target: ControllerEntry,
        homeAsBack: Boolean,
        comboHoldKillApp: Boolean,
    ) {
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
        val command =
            "nice -n -20 ${binary.absolutePath.shellQuote()} ${source.path.shellQuote()} ${target.path.shellQuote()}$homeAsBackArg$comboHoldKillAppArg$pidFileArg$heartbeatFileArg >/dev/null 2>&1 & echo \$! > ${pidFile.absolutePath.shellQuote()}; chmod 666 ${pidFile.absolutePath.shellQuote()} ${heartbeatFile.absolutePath.shellQuote()}"
        runSu(command)
    }

    private fun ensureBinaryInstalled(): File {
        val binDir = File(filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }

        val target = File(binDir, "input_mirror")
        val temp = File(binDir, "input_mirror.tmp")
        assets.open("input_mirror/input_mirror").use { input ->
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

    private fun getConnectedControllerEntries(): List<ControllerEntry> {
        val procEntries = runShell("cat /proc/bus/input/devices")
            .split(Regex("\\n\\s*\\n"))
            .mapNotNull { parseProcInputBlock(it) }

        return InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> isPhysicalGameController(device) }
            .mapNotNull { controller -> findProcEntryForController(controller, procEntries) }
            .distinctBy { it.path }
            .sortedBy { it.controllerNumber }
            .toList()
    }

    private fun parseProcInputBlock(block: String): ControllerEntry? {
        val identityMatch = Regex("I: Bus=([0-9A-Fa-f]+) Vendor=([0-9A-Fa-f]+) Product=([0-9A-Fa-f]+)")
            .find(block)
            ?: return null
        val name = Regex("N: Name=\"([^\"]+)\"").find(block)?.groupValues?.getOrNull(1) ?: return null
        val handlersLine = Regex("H: Handlers=(.+)").find(block)?.groupValues?.getOrNull(1) ?: return null
        val eventName = Regex("\\bevent\\d+\\b").find(handlersLine)?.value ?: return null

        return ControllerEntry(
            name = name,
            path = "/dev/input/$eventName",
            eventName = eventName,
            bus = identityMatch.groupValues[1].toInt(16),
            vendorId = identityMatch.groupValues[2].toInt(16),
            productId = identityMatch.groupValues[3].toInt(16),
        )
    }

    private fun findProcEntryForController(
        controller: InputDevice,
        entries: List<ControllerEntry>,
    ): ControllerEntry? {
        val normalizedName = controller.name.lowercase()
        val candidates = entries.filter { entry ->
            if (!File(entry.path).exists()) {
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
                compareByDescending<ControllerEntry> {
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

    private fun findSavedDevice(path: String?, guid: String?, devices: List<ControllerEntry>): ControllerEntry? {
        if (!guid.isNullOrBlank()) {
            devices.find { it.guid == guid }?.let { return it }
        }

        if (!path.isNullOrBlank()) {
            devices.find { it.path == path }?.let { return it }
        }

        return null
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

    private fun isMirrorProcessRunning(): Boolean {
        if (isHeartbeatFresh()) {
            return true
        }

        val pid = runCatching { getMirrorPidFile().readText().trim().toIntOrNull() }.getOrNull()
            ?: return false
        val procDir = File("/proc/$pid")
        if (!procDir.exists()) {
            return false
        }

        return readProcessState(pid) != "Z"
    }

    private fun readProcessState(pid: Int): String? {
        return runCatching {
            val stat = File("/proc/$pid/stat").readText()
            val stateStart = stat.lastIndexOf(") ") + 2
            stat.substring(stateStart).trim().split(Regex("\\s+")).firstOrNull()
        }.getOrNull()
    }

    private fun isHeartbeatFresh(): Boolean {
        val heartbeatFile = getMirrorHeartbeatFile()
        if (!heartbeatFile.exists()) {
            return false
        }

        return System.currentTimeMillis() - heartbeatFile.lastModified() <= HEARTBEAT_STALE_MS
    }

    private fun getMirrorPidFile(): File = File(filesDir, "input_mirror.pid")

    private fun getMirrorHeartbeatFile(): File = File(filesDir, "input_mirror.heartbeat")

    private fun runSu(command: String): String = runProcess(arrayOf("su", "-c", command))

    private fun runShell(command: String): String = runProcess(arrayOf("sh", "-c", command))

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

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mirror supervisor",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Docking Enhancer")
            .setContentText("Keeping controller mirror ready")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private data class ControllerEntry(
        val name: String,
        val path: String,
        val eventName: String,
        val bus: Int,
        val vendorId: Int,
        val productId: Int,
        val guid: String = "",
        val controllerNumber: Int = Int.MAX_VALUE,
    ) {
        val eventNumber: Int
            get() = eventName.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE

        val isOdinVirtualController: Boolean
            get() = bus == BUS_USB && vendorId == ODIN_VENDOR_ID
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "input_mirror_supervisor"
        private const val SUPERVISOR_INTERVAL_MS = 2500L
        private const val SUPERVISOR_IDLE_INTERVAL_MS = 8000L
        private const val SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS = 6000L
        private const val RESTART_THROTTLE_MS = 10000L
    }
}
