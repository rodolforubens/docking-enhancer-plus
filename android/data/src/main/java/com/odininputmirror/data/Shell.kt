package com.odininputmirror.data

import com.topjohnwu.superuser.Shell as LibSuShell

internal class Shell {
    // Both helpers route through libsu's main shell, which is a persistent root (su) session
    // when root is available. runShell keeps its name for callers that only need a plain shell
    // command; running it under the root session is harmless and avoids a second process.
    fun runSu(command: String): String = run(command)

    fun runShell(command: String): String = run(command)

    private fun run(command: String): String {
        // Run in a subshell so an `exit N` inside the command terminates only the subshell, not
        // libsu's persistent root session. Without this, commands like STOP_MIRROR_COMMAND that
        // end in `exit` would kill the shared shell and libsu would report a spurious failure.
        val result = LibSuShell.cmd("($command)").exec()
        val output = (result.out + result.err).joinToString("\n")
        if (!result.isSuccess) {
            throw IllegalStateException("Command failed (${result.code}): $output")
        }
        return output
    }
}

internal fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"
