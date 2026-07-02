package com.odininputmirror.data

import android.content.Context
import java.io.File

internal class InputMirrorFiles(private val context: Context) {
    val pidFile: File
        get() = File(context.filesDir, "input_mirror.pid")

    val heartbeatFile: File
        get() = File(context.filesDir, "input_mirror.heartbeat")

    fun ensureBinaryInstalled(): File {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }

        val target = File(binDir, "input_mirror")

        // The bundled binary only changes when the app itself is updated, so skip re-extracting the
        // asset on every mirror start: keep it if the installed copy is newer than the last package
        // update.
        val lastUpdate = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        if (target.exists() && target.length() > 0L && target.lastModified() >= lastUpdate) {
            return target
        }

        val temp = File(binDir, "input_mirror.tmp")
        context.assets.open("input_mirror/input_mirror").use { input ->
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

    fun prepareProcessFiles() {
        // Clear any stale pid/heartbeat from a previous run. Do NOT pre-create empty files: the
        // daemon writes and chmods its own, and a freshly-touched heartbeat would otherwise read as
        // "fresh" (recent mtime) before the daemon is even alive, masking a failed launch.
        pidFile.delete()
        heartbeatFile.delete()
    }

    fun clearProcessFiles() {
        pidFile.delete()
        heartbeatFile.delete()
    }
}
