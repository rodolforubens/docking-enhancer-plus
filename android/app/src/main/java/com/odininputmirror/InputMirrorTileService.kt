package com.odininputmirror

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.io.File

class InputMirrorTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = if (isMirrorRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (isMirrorRunning()) {
            runCatching { runSu(STOP_MIRROR_COMMAND) }
            qsTile?.state = Tile.STATE_INACTIVE
        } else {
            val source = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SOURCE, null)
            val target = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TARGET, null)

            if (!source.isNullOrBlank() && !target.isNullOrBlank() && source != target) {
                val binary = ensureBinaryInstalled()
                val command = "nice -n -20 ${binary.absolutePath.shellQuote()} ${source.shellQuote()} ${target.shellQuote()} >/dev/null 2>&1 &"
                runCatching { runSu(command) }
                qsTile?.state = Tile.STATE_ACTIVE
            } else {
                qsTile?.state = Tile.STATE_UNAVAILABLE
            }
        }

        qsTile?.updateTile()
    }

    private fun isMirrorRunning(): Boolean {
        return runCatching {
            runProcess(arrayOf("su", "-c", IS_MIRROR_RUNNING_COMMAND))
            true
        }.getOrDefault(false)
    }

    private fun ensureBinaryInstalled(): File {
        val binDir = File(filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }

        val target = File(binDir, "input_mirror")
        val temp = File(binDir, "input_mirror.tmp")
        assets.open("input_mirror/input_mirror").use { input ->
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

    private fun runSu(command: String): String = runProcess(arrayOf("su", "-c", command))

    private fun runProcess(command: Array<String>): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(output.ifBlank { "Command failed: $exitCode" })
        }
        return output
    }

    companion object {
        const val PREFS = "input_mirror"
        const val KEY_SOURCE = "source"
        const val KEY_TARGET = "target"
    }
}

private fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

private const val IS_MIRROR_RUNNING_COMMAND =
    "for pid in \$(pidof input_mirror 2>/dev/null); do state=\$(cat /proc/\$pid/stat 2>/dev/null | awk '{print \$3}'); [ \"\$state\" != \"Z\" ] && exit 0; done; exit 1"
private const val STOP_MIRROR_COMMAND =
    "pids=\$(pidof input_mirror 2>/dev/null); [ -z \"\$pids\" ] && exit 0; kill -TERM \$pids 2>/dev/null; sleep 0.15; for pid in \$pids; do [ -d /proc/\$pid ] && kill -KILL \$pid 2>/dev/null; done; exit 0"
