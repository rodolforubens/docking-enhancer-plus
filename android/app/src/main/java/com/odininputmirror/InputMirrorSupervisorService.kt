package com.odininputmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.odininputmirror.data.InputMirrorGraph
import com.odininputmirror.domain.usecase.RestartDecision

class InputMirrorSupervisorService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private lateinit var graph: InputMirrorGraph

    override fun onCreate() {
        super.onCreate()
        graph = InputMirrorGraph(this)
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
                val now = System.currentTimeMillis()
                val decision = graph.restartMirrorIfNeeded(restartAllowed = now >= nextRestartAllowedAt)
                when (decision) {
                    RestartDecision.StopSupervisor -> {
                        stopSelf()
                        break
                    }
                    RestartDecision.Idle -> sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                    RestartDecision.Running -> sleepMs = SUPERVISOR_INTERVAL_MS
                    RestartDecision.WaitingForDevice -> sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    RestartDecision.Restarted -> {
                        nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                        sleepMs = SUPERVISOR_INTERVAL_MS
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

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "input_mirror_supervisor"
        private const val SUPERVISOR_INTERVAL_MS = 2500L
        private const val SUPERVISOR_IDLE_INTERVAL_MS = 8000L
        private const val SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS = 6000L
        private const val RESTART_THROTTLE_MS = 10000L
    }
}
