package com.odininputmirror.data

import android.annotation.SuppressLint
import android.os.DeadObjectException
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

    // The binder is cached but NOT immortal: if the firmware service restarts, the old IBinder goes
    // dead and every transact would fail forever. [binder] re-resolves a dead/missing handle so the
    // mirror recovers instead of silently failing until the app process is killed.
    @Volatile
    private var cachedBinder: IBinder? = null

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? {
        val current = cachedBinder
        if (current != null && current.isBinderAlive) {
            return current
        }
        return resolveBinder().also { cachedBinder = it }
    }

    private fun resolveBinder(): IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as? IBinder
    }.getOrNull()

    /** Runs [command] through PServer as root and returns its stdout, or a failure. */
    fun executeAsRoot(command: String): Result<String?> {
        val target = binder() ?: return Result.failure(IllegalStateException("$SERVICE_NAME not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            // Second element "1" is PULSE's run-as-root flag; kept identical to match the service's
            // expected argument shape.
            data.writeStringArray(arrayOf(command, "1"))
            val handled = target.transact(TRANSACTION_EXEC, data, reply, 0)
            if (handled) {
                Result.success(decodeReply(reply))
            } else {
                Result.failure(IllegalStateException("$SERVICE_NAME did not handle the exec transaction"))
            }
        } catch (throwable: Throwable) {
            if (throwable is DeadObjectException) {
                // Drop the dead handle so the next call re-resolves the (possibly restarted) service.
                cachedBinder = null
            }
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
