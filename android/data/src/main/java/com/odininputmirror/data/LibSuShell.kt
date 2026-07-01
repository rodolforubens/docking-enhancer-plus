package com.odininputmirror.data

import com.topjohnwu.superuser.Shell as LibSu

/**
 * [MirrorShell] backed by libsu's persistent root (su) session. This is the fallback for devices
 * without PServerBinder, and preserves the exact behaviour of the original [Shell] helper.
 */
internal class LibSuShell : MirrorShell {
    override val isAvailable: Boolean
        get() = runCatching { LibSu.getShell().isRoot }.getOrDefault(false)

    override fun exec(command: String) {
        run(command)
    }

    override fun read(command: String): String = run(command)

    override fun launchDaemon(command: String) {
        // libsu's root session persists, so a plain background is enough and its own exec returns
        // immediately. The daemon writes its own pid/heartbeat files.
        run("$command >/dev/null 2>&1 &")
    }

    private fun run(command: String): String {
        // Run in a subshell so an `exit N` inside the command terminates only the subshell, not
        // libsu's persistent root session (e.g. STOP_MIRROR_COMMAND ends in `exit`).
        val result = LibSu.cmd("($command)").exec()
        val output = (result.out + result.err).joinToString("\n")
        if (!result.isSuccess) {
            throw IllegalStateException("Command failed (${result.code}): $output")
        }
        return output
    }
}
