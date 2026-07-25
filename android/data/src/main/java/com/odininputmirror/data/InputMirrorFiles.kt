package com.odininputmirror.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal class InputMirrorFiles(
    private val filesDir: File,
    private val openBinaryAsset: () -> InputStream,
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        openBinaryAsset = { context.assets.open(BINARY_ASSET_PATH) },
    )

    val pidFile: File
        get() = File(filesDir, "input_mirror.pid")

    val heartbeatFile: File
        get() = File(filesDir, "input_mirror.heartbeat")

    // Record of the /dev/input nodes the running daemon has hidden (path + device number). Survives
    // a crash so the daemon's --heal mode can restore an orphaned node the crash left behind.
    val hiddenStateFile: File
        get() = File(filesDir, "input_mirror.hidden")

    fun ensureBinaryInstalled(): File {
        val binDir = File(filesDir, "bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }

        val target = File(binDir, "input_mirror")

        // Reuse the installed copy only when its content hashes equal to the bundled asset. The
        // binary is executed AS ROOT, so a stale or tampered copy must never run: content hashing
        // (rather than an mtime-vs-package-update shortcut) re-extracts on any mismatch. Mirror
        // starts are rare (dock events), so hashing both sides is cheap.
        val assetSha = openBinaryAsset().use { it.sha256() }
        val installedSha = runCatching { target.inputStream().use { it.sha256() } }.getOrNull()
        if (assetSha.contentEquals(installedSha)) {
            return target
        }

        val temp = File(binDir, "input_mirror.tmp")
        openBinaryAsset().use { input ->
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

    private companion object {
        const val BINARY_ASSET_PATH = "input_mirror/input_mirror"
    }
}

private fun InputStream.sha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest()
}
