package com.odininputmirror

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
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
        // Application.onCreate also runs when the process is spawned in the background (e.g. for a
        // broadcast); if that context isn't exempt from the FGS-from-background restriction the
        // start throws — never crash the whole app over it, the next foreground entry retries.
        runCatching {
            val intent = Intent(this, InputMirrorSupervisorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure { Log.w(TAG, "Could not start supervisor from Application.onCreate", it) }
    }

    private companion object {
        const val TAG = "MainApplication"
    }
}
