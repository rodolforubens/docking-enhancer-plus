package com.odininputmirror.data

import android.content.Context
import java.io.File
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
    context: Context,
    private val pserver: PServerExec = PServerExec(),
) : MirrorShell {
    private val appContext = context.applicationContext

    override val isAvailable: Boolean get() = pserver.isAvailable

    override fun exec(command: String) {
        transact(command)
    }

    override fun read(command: String): String {
        val out = File(appContext.filesDir, STAGE_FILE)
        val outQ = out.absolutePath.shellQuote()
        // Redirect the real output to a file (dodging the first-line-only reply) and make it
        // app-readable. The command runs in a subshell so the redirect captures ALL of its output,
        // not just its last simple command. No exit code is available; an empty file means the
        // command produced nothing.
        transact("($command) > $outQ 2>/dev/null; chmod 666 $outQ")
        return runCatching { out.readText() }.getOrDefault("")
    }

    override fun launchDaemon(command: String) {
        // Run the daemon in the FOREGROUND inside a script, then background the whole `sh`. A direct
        // `bin &` gets SIGHUP'd when the transact returns (input_mirror catches SIGHUP → exits); an
        // intermediate live `sh` keeps it alive. Do NOT use setsid — a session leader also gets
        // SIGHUP'd. Validated on-device (PServerProbe plain-script strategy).
        val launcher = File(appContext.filesDir, DAEMON_SCRIPT)
        launcher.writeText("$command >/dev/null 2>&1\n")
        launcher.setReadable(true, false)
        launcher.setExecutable(true, false)
        transact("sh ${launcher.absolutePath.shellQuote()} </dev/null >/dev/null 2>&1 &")
    }

    private fun transact(command: String): String? = lock.withLock {
        pserver.executeAsRoot(command).getOrNull()
    }

    private companion object {
        const val STAGE_FILE = "pserver_read.out"
        const val DAEMON_SCRIPT = "pserver_daemon.sh"
        // PServerBinder cannot handle concurrent transacts; serialize all callers process-wide.
        val lock = ReentrantLock()
    }
}
