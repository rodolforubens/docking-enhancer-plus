package com.odininputmirror

import android.app.Application
import android.content.Intent
import android.os.Build
import com.odininputmirror.data.isPServerSupported

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The mirror drives the stock firmware's PServerBinder service (no root). On devices that
        // don't ship it there is nothing to supervise, so don't start the service at all.
        if (isPServerSupported()) {
            startSupervisor()
        }
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
