package com.odininputmirror.data

/**
 * Runs privileged shell commands for the mirror. Two backends implement it:
 *  - [LibSuShell]: a persistent `su` (root) session via libsu — the original path.
 *  - [PServerShell]: the stock firmware's PServerBinder service — no root required.
 *
 * The two methods carve the callers into the shapes both backends can honor. PServer cannot
 * report an exit code and returns only the first line of stdout, so callers must not rely on
 * either: use [exec] when the result is irrelevant, and [read] (which stages full output through
 * a file on the PServer backend) when the command's stdout is needed. Success/failure that used
 * to be an exit code must become a token in the command's stdout.
 */
internal interface MirrorShell {
    /** True when this backend can actually run privileged commands on this device. */
    val isAvailable: Boolean

    /** Fire-and-forget: run [command] as root; stdout and success are ignored. */
    fun exec(command: String)

    /** Run [command] as root and return its full stdout (multi-line safe on both backends). */
    fun read(command: String): String

    /**
     * Launch a long-running daemon. [command] is the FOREGROUND invocation (no trailing `&`); the
     * implementation backgrounds it so the daemon outlives this call. This is separate from [exec]
     * because the PServer backend cannot just append `&` — a directly backgrounded child receives
     * SIGHUP when the transact returns and input_mirror handles SIGHUP by exiting. The daemon must
     * run in the foreground under a live intermediate `sh` that is itself backgrounded (validated
     * on-device via PServerProbe). The daemon is expected to write its own pid/heartbeat files.
     */
    fun launchDaemon(command: String)
}
