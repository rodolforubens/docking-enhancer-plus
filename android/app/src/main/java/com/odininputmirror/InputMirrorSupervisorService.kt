package com.odininputmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.odininputmirror.data.InputMirrorGraph
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.model.MirrorStatus
import com.odininputmirror.domain.usecase.AutoMirrorDecision

class InputMirrorSupervisorService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private lateinit var graph: InputMirrorGraph
    private lateinit var notificationManager: NotificationManager
    private var supervisorState = SupervisorState.WaitingForDock

    override fun onCreate() {
        super.onCreate()
        graph = InputMirrorGraph(this, forceDockMode = BuildConfig.FORCE_DOCK_MODE_FOR_DEV)
        notificationManager = getSystemService(NotificationManager::class.java)
        startForeground(NOTIFICATION_ID, buildNotification(supervisorState))
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
        // Without the PServerBinder service there is nothing to drive; surface it and stop the
        // worker (the foreground notification stays as Unsupported).
        if (!graph.isSupportedDevice) {
            updateSupervisorState(SupervisorState.Unsupported)
            return
        }

        var nextRestartAllowedAt = 0L
        var nextStartRetryAllowedAt = 0L
        var sleepMs = SUPERVISOR_INTERVAL_MS

        while (running) {
            try {
                val now = System.currentTimeMillis()
                val settings = graph.settingsRepository.getSettings()
                val dockActive = graph.dockStateRepository.isDockActive()
                // Read the controllers every tick regardless of dock state and publish them for the
                // UI to observe — the screen shows connected controllers even undocked (for setup),
                // while the auto-mirror decision below still uses the dock-gated list.
                val allDevices = graph.inputDeviceRepository.getConnectedControllers()
                val mirrorRunning = graph.processRepository.isRunning()
                publishSnapshot(settings, dockActive, allDevices, mirrorRunning)

                if (!settings.autoMirrorEnabled) {
                    if (mirrorRunning || settings.expectedRunning) {
                        runCatching { graph.stopMirror() }
                    }
                    updateSupervisorState(SupervisorState.Disabled)
                    sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                    Thread.sleep(sleepMs)
                    continue
                }
                val devices = if (dockActive) allDevices else emptyList()
                val decision = graph.resolveAutoMirrorDecision(
                    dockActive = dockActive,
                    devices = devices,
                    settings = settings,
                    mirrorRunning = mirrorRunning,
                    restartAllowed = now >= nextRestartAllowedAt && now >= nextStartRetryAllowedAt,
                )

                when (decision) {
                    AutoMirrorDecision.StopForDock -> {
                        if (mirrorRunning || settings.expectedRunning) {
                            runCatching { graph.stopMirror() }
                        }
                        updateSupervisorState(SupervisorState.WaitingForDock)
                        sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForInternalController -> {
                        updateSupervisorState(SupervisorState.WaitingForInternalController)
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForExternalController -> {
                        updateSupervisorState(SupervisorState.WaitingForExternalController)
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForRestartThrottle -> {
                        if (supervisorState != SupervisorState.MirrorStartFailed) {
                            updateSupervisorState(SupervisorState.Restarting)
                        }
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    AutoMirrorDecision.Running -> {
                        updateSupervisorState(SupervisorState.Active)
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    is AutoMirrorDecision.Start -> {
                        updateSupervisorState(SupervisorState.Starting)
                        if (startMirror(decision.request)) {
                            updateSupervisorState(SupervisorState.Active)
                        } else {
                            nextStartRetryAllowedAt = now + START_RETRY_BACKOFF_MS
                        }
                        nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    is AutoMirrorDecision.Restart -> {
                        updateSupervisorState(SupervisorState.Restarting)
                        runCatching { graph.stopMirror() }
                        if (startMirror(decision.request)) {
                            updateSupervisorState(SupervisorState.Active)
                        } else {
                            nextStartRetryAllowedAt = now + START_RETRY_BACKOFF_MS
                        }
                        nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                }
            } catch (_: Exception) {
                // Keep the supervisor alive; transient root/device failures are expected during reconnects.
                updateSupervisorState(SupervisorState.Error)
                sleepMs = SUPERVISOR_ERROR_INTERVAL_MS
            }

            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun publishSnapshot(
        settings: MirrorSettings,
        dockActive: Boolean,
        devices: List<ControllerDevice>,
        mirrorRunning: Boolean,
    ) {
        MirrorStateStore.publish(
            devices = devices,
            status = MirrorStatus(
                running = mirrorRunning,
                expectedRunning = settings.expectedRunning,
                source = settings.source,
                target = settings.target,
                sourceGuid = settings.sourceGuid,
                targetGuid = settings.targetGuid,
                homeAsBack = settings.homeAsBack,
                comboHoldKillApp = settings.comboHoldKillApp,
                autoRestart = settings.autoRestart,
                autoMirrorEnabled = settings.autoMirrorEnabled,
                docked = dockActive,
                manualInternalGuid = settings.manualInternalGuid,
            ),
        )
    }

    private fun startMirror(request: MirrorStartRequest): Boolean {
        return runCatching {
            graph.startMirror(request)
            true
        }.getOrElse {
            updateSupervisorState(SupervisorState.MirrorStartFailed)
            false
        }
    }

    private fun updateSupervisorState(state: SupervisorState) {
        if (supervisorState == state) {
            return
        }
        supervisorState = state
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: SupervisorState): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mirror supervisor",
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags)

        return builder
            .setContentTitle("Docking Enhancer")
            .setContentText(state.notificationText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private enum class SupervisorState(val notificationText: String) {
        WaitingForDock("Waiting for external display"),
        Disabled("Automatic mirror disabled"),
        WaitingForInternalController("Waiting for Odin controller"),
        WaitingForExternalController("Waiting for external controller"),
        Starting("Starting dock mirror"),
        Active("Dock mirror active"),
        Restarting("Restarting dock mirror"),
        MirrorStartFailed("Could not start the mirror"),
        Unsupported("This device is not supported"),
        Error("Mirror supervisor error"),
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "input_mirror_supervisor"
        private const val SUPERVISOR_INTERVAL_MS = 2500L
        private const val SUPERVISOR_IDLE_INTERVAL_MS = 8000L
        private const val SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS = 6000L
        private const val SUPERVISOR_ERROR_INTERVAL_MS = 15000L
        private const val RESTART_THROTTLE_MS = 10000L
        private const val START_RETRY_BACKOFF_MS = 60000L
    }
}
