package com.odininputmirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class InputMirrorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        runCatching {
            val serviceIntent = Intent(context, InputMirrorSupervisorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
