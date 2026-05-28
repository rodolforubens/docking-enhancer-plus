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
            runProcess(arrayOf("su", "-c", "pidof input_mirror >/dev/null"))
            true
        }.getOrDefault(false)
    }

    private fun ensureBinaryInstalled(): File {
        val binDir = File(filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }

        val target = File(binDir, "input_mirror")
        if (!target.exists() || target.length() == 0L) {
            assets.open("input_mirror/input_mirror").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
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

private const val STOP_MIRROR_COMMAND = "kill -TERM \$(pidof input_mirror 2>/dev/null) 2>/dev/null || true"
