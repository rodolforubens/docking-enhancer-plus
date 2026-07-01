package com.odininputmirror.data

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.Charset

/**
 * No-root privileged command execution via the stock firmware's `PServerBinder` service.
 *
 * This is the same "ClusterTune" technique PULSE uses: the handheld's own firmware ships a
 * privileged system service that runs a shell command as root. We grab its binder by name via
 * reflection and drive it directly with a raw transact — no Magisk, no user-granted su.
 *
 * Mechanism mirrored from keiretrogaming/pulse (RootExec.kt):
 *   ServiceManager.getService("PServerBinder") -> IBinder
 *   binder.transact(0, data{ writeStringArray([cmd, "1"]) }, reply, 0)
 *   reply.createByteArray() -> stdout as UTF text
 *
 * NOTE: PULSE only ever runs *short, fire-and-forget* commands (sysfs writes + `cat` reads).
 * Whether this service can (a) open /dev/input & /dev/uinput and (b) keep a backgrounded daemon
 * alive after transact returns is exactly what [PServerProbe] exists to answer. Do not wire this
 * into the real mirror start path until the probe confirms both on-device.
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class PServerExec {

    private val binder: IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as IBinder
    }.getOrNull()

    val isAvailable: Boolean get() = binder != null

    /** Runs [command] through PServer as root and returns its stdout, or a failure. */
    fun executeAsRoot(command: String): Result<String?> {
        val target = binder ?: return Result.failure(IllegalStateException("$SERVICE_NAME not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            // Second element "1" is PULSE's run-as-root flag; kept identical to match the service's
            // expected argument shape.
            data.writeStringArray(arrayOf(command, "1"))
            target.transact(TRANSACTION_EXEC, data, reply, 0)
            Result.success(decodeReply(reply))
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun decodeReply(reply: Parcel): String? =
        reply.createByteArray()
            ?.toString(Charset.defaultCharset())
            ?.trim()
            ?.let { if (it == "null") null else it }

    private companion object {
        const val SERVICE_NAME = "PServerBinder"
        const val TRANSACTION_EXEC = 0
    }
}
