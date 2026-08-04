package com.odininputmirror

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Started unconditionally, and the service decides. Asking here whether the device ships
        // PServerBinder meant a BLOCKING binder transact on the main thread at process start — and on
        // hardware that publishes the service without answering it (the Odin 2 Mini) that is a
        // synchronous call to something dead, on the thread that draws. The supervisor makes the same
        // check on its worker and stands itself down if there is nothing to drive.
        startSupervisor()
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
