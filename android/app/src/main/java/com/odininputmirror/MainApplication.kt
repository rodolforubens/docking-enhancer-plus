package com.odininputmirror

import android.app.Application
import android.content.Intent
import android.os.Build
import com.topjohnwu.superuser.Shell

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10),
        )
        startSupervisor()
    }

    private fun startSupervisor() {
        val intent = Intent(this, InputMirrorSupervisorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
