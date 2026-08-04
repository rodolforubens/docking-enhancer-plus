package com.odininputmirror.data

import android.annotation.SuppressLint
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.Charset

/**
 * Runs a privileged command as root and returns its (first-line) stdout, or a failure. The seam
 * that lets [PServerShell] be unit-tested without a live binder; the production impl is [PServerExec].
 */
internal interface PServerTransactor {
    /** True when the backend can actually run privileged commands on this device. */
    val isAvailable: Boolean

    /**
     * True when the service is published but will not answer — distinct from absent, and worth
     * telling apart: "your device doesn't ship this" is plainly wrong on hardware that does.
     */
    val isRegisteredButUnresponsive: Boolean get() = false

    /** Runs [command] as root; returns its stdout reply, or a failure if the transact didn't land. */
    fun executeAsRoot(command: String): Result<String?>
}

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
internal class PServerExec : PServerTransactor {

    // The binder is cached but NOT immortal: if the firmware service restarts, the old IBinder goes
    // dead and every transact would fail forever. [binder] re-resolves a dead/missing handle so the
    // mirror recovers instead of silently failing until the app process is killed.
    @Volatile
    private var cachedBinder: IBinder? = null

    // Whether the service actually answered a transaction, cached per resolved binder. Null until
    // probed.
    @Volatile
    private var respondsToTransactions: Boolean? = null

    /*
     * Registered is not the same as working. The AYN Odin 2 Mini publishes PServerBinder — the
     * handle resolves and `isBinderAlive` is true — but every transaction against it throws
     * DeadObjectException, because the process behind the service is gone. Judging availability by
     * the handle alone left that device with an app that looked installed and simply did nothing:
     * no controllers, no error, nothing to act on. So availability means "answered a real
     * transaction", and a device that cannot be driven is told so.
     */
    override val isAvailable: Boolean
        get() {
            if (binder() == null) {
                return false
            }
            respondsToTransactions?.let { return it }
            // Cheapest command there is; only whether the transaction lands matters.
            return executeAsRoot("true").isSuccess.also { respondsToTransactions = it }
        }

    /** True when the service exists but refuses to answer — the Odin 2 Mini case. */
    override val isRegisteredButUnresponsive: Boolean
        get() = binder() != null && !isAvailable

    private fun binder(): IBinder? {
        val current = cachedBinder
        if (current != null && current.isBinderAlive) {
            return current
        }
        // A fresh handle deserves a fresh verdict: the old one may have been probed while dead.
        respondsToTransactions = null
        return resolveBinder().also { cachedBinder = it }
    }

    private fun resolveBinder(): IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as? IBinder
    }.getOrNull()

    /** Runs [command] through PServer as root and returns its stdout, or a failure. */
    override fun executeAsRoot(command: String): Result<String?> {
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
                respondsToTransactions = false
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

    companion object {
        /**
         * One per process.
         *
         * [isAvailable] caches its verdict, but the cache lives on the instance — so a fresh
         * PServerExec per caller threw that away and paid the blocking probe transact again. With
         * the supervisor, the screen and the two startup entry points each building their own, the
         * device was probed several times over at process start, on the main thread, and on hardware
         * where the service is registered but dead (the Odin 2 Mini) every one of those was a
         * synchronous call into a system service that never answers.
         */
        val shared: PServerExec by lazy { PServerExec() }

        private const val SERVICE_NAME = "PServerBinder"
        private const val TRANSACTION_EXEC = 0
    }
}
