package com.odininputmirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class InputMirrorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        // No PServerBinder probe here: onReceive runs on the main thread, the probe blocks on a
        // binder transact, and a receiver that overruns its window is killed. The supervisor checks
        // on its own worker and stops itself when the service is missing.
        runCatching {
            val serviceIntent = Intent(context, InputMirrorSupervisorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure { Log.w("InputMirrorBootReceiver", "Could not start supervisor on boot", it) }
    }
}
