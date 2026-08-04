package com.odininputmirror.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal class InputMirrorFiles(
    private val filesDir: File,
    // Injected for the same reason openBinaryAsset is: android.system.Os is a stub on the JVM, and
    // the file layout deserves to stay unit-testable without a device or Robolectric. Declared
    // ahead of openBinaryAsset so that stays the last parameter, and callers keep passing it as a
    // trailing lambda.
    private val makeFifo: (String) -> Unit = { path ->
        Os.mkfifo(path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
    },
    private val isFifo: (String) -> Boolean = { path ->
        OsConstants.S_ISFIFO(Os.stat(path).st_mode)
    },
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

    // The live options the daemon re-reads on demand, and the fifo it is told to re-read them on.
    val configFile: File
        get() = File(filesDir, "input_mirror.config.json")

    // Append-only log of what the daemon saw during a capture step.
    val captureFile: File
        get() = File(filesDir, "input_mirror.capture")

    val controlFifo: File
        get() = File(filesDir, "input_mirror.ctl")

    /**
     * Create the control fifo if it isn't already there.
     *
     * It lives in the app's own directory and is created by the app, which is the whole point: the
     * app owns the path so no privileged call is needed to make it, and the daemon — running as
     * root — can read it. That keeps every settings change off the PServer transaction queue, which
     * is serialized process-wide and would otherwise be in the path of a toggle.
     */
    fun ensureControlFifo(): File {
        val fifo = controlFifo
        // A leftover of the wrong kind (a plain file from an interrupted write, say) would make
        // mkfifo fail with EEXIST forever, so replace anything that is not already a fifo.
        val existingIsFifo = runCatching { isFifo(fifo.absolutePath) }.getOrDefault(false)
        if (existingIsFifo) {
            return fifo
        }
        fifo.delete()
        makeFifo(fifo.absolutePath)
        return fifo
    }

    /**
     * Replace the config the daemon reads, atomically.
     *
     * Written to a sibling temp file and renamed into place: rename within one filesystem is atomic,
     * so the daemon can never read a half-written document, and neither side needs a lock.
     */
    fun writeConfig(json: String) {
        val temp = File(filesDir, "${configFile.name}.tmp")
        temp.writeText(json)
        if (!temp.renameTo(configFile)) {
            temp.delete()
            throw IOException("Could not replace ${configFile.name}")
        }
    }

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
