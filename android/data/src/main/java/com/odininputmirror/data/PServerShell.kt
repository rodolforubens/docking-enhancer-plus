package com.odininputmirror.data

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [MirrorShell] backed by the stock firmware's PServerBinder service — root without su.
 *
 * Two properties of the service shape this implementation (both validated on-device, see the
 * [PServerProbe] spike):
 *  - It cannot service overlapping transacts, so every call is serialized behind [lock].
 *  - It returns only the FIRST line of a command's stdout and no exit code. [exec] therefore
 *    discards the reply, and [read] stages the command's full output through a world-readable
 *    file that the app reads back.
 *
 * Note for daemon launches: pass a plain backgrounded command to [exec] (`... &`). Do NOT wrap it
 * in `setsid` — input_mirror handles SIGHUP and a setsid session leader gets SIGHUP'd into a clean
 * exit. A plain background child survives the transact return.
 */
internal class PServerShell(
    private val filesDir: File,
    private val pserver: PServerTransactor,
) : MirrorShell {
    constructor(context: Context, pserver: PServerTransactor = PServerExec.shared) : this(
        filesDir = context.applicationContext.filesDir,
        pserver = pserver,
    )

    override val isAvailable: Boolean get() = pserver.isAvailable

    override val isRegisteredButUnresponsive: Boolean get() = pserver.isRegisteredButUnresponsive

    override fun exec(command: String): Boolean = transact(command)

    override fun read(command: String): String {
        // A UNIQUE staging file per call. read() runs concurrently with the supervisor's own poll
        // reads; a single shared file would let one call's output clobber another's in the window
        // between the (locked) transact and the (unlocked) readText, and would return stale content
        // from a prior call whenever a transact fails (the command never truncates the file). A
        // fresh name isolates each caller: on failure or empty output the file simply isn't there,
        // which reads back as "".
        val out = File(filesDir, "pserver_read_${readSeq.incrementAndGet()}.out")
        val outQ = out.absolutePath.shellQuote()
        return try {
            // Redirect the real output to a file (dodging the first-line-only reply) and make it
            // app-readable. The command runs in a subshell so the redirect captures ALL of its
            // output, not just its last simple command. No exit code is available; an empty file
            // means the command produced nothing. 644: the app only ever READS this file back.
            if (!transact("($command) > $outQ 2>/dev/null; chmod 644 $outQ")) {
                ""
            } else {
                runCatching { out.readText() }.getOrDefault("")
            }
        } finally {
            out.delete()
        }
    }

    override fun launchDaemon(command: String): Boolean {
        // Run the daemon in the FOREGROUND inside a script, then background the whole `sh`. A direct
        // `bin &` gets SIGHUP'd when the transact returns (input_mirror catches SIGHUP → exits); an
        // intermediate live `sh` keeps it alive. Do NOT use setsid — a session leader also gets
        // SIGHUP'd. Validated on-device (PServerProbe plain-script strategy).
        // Write to a temp file and atomically rename over the real script. A plain writeText would
        // truncate/rewrite the SAME inode a still-starting `sh` from a previous launch may be
        // mid-reading (sh reads its script incrementally) — the rename gives the new launch a fresh
        // inode while any old reader keeps its own.
        val launcher = File(filesDir, DAEMON_SCRIPT)
        val staging = File(filesDir, "$DAEMON_SCRIPT.tmp")
        staging.writeText("$command >/dev/null 2>&1\n")
        staging.setReadable(true, false)
        staging.setExecutable(true, false)
        if (!staging.renameTo(launcher)) {
            // Same-directory rename should never fail; if it somehow does, fall back to the direct
            // write rather than not launching at all.
            launcher.writeText("$command >/dev/null 2>&1\n")
            launcher.setReadable(true, false)
            launcher.setExecutable(true, false)
            staging.delete()
        }
        return transact("sh ${launcher.absolutePath.shellQuote()} </dev/null >/dev/null 2>&1 &")
    }

    // True when the transact reached the service (delivery only — PServer exposes no exit code).
    private fun transact(command: String): Boolean = lock.withLock {
        pserver.executeAsRoot(command)
            .onFailure { Log.w(TAG, "PServer transact failed", it) }
            .isSuccess
    }

    private companion object {
        const val TAG = "PServerShell"
        const val DAEMON_SCRIPT = "pserver_daemon.sh"
        // PServerBinder cannot handle concurrent transacts; serialize all callers process-wide.
        val lock = ReentrantLock()
        // Monotonic suffix for per-call read staging files (unique within the process).
        val readSeq = AtomicLong(0)
    }
}
