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
        pidFile.writeText("")
        pidFile.setReadable(true, false)
        pidFile.setWritable(true, false)
        heartbeatFile.writeText("")
        heartbeatFile.setReadable(true, false)
        heartbeatFile.setWritable(true, false)
    }

    fun clearProcessFiles() {
        pidFile.delete()
        heartbeatFile.delete()
    }
}
