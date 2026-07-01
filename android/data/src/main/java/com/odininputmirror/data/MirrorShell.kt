package com.odininputmirror.data

/**
 * Runs privileged shell commands for the mirror, implemented by [PServerShell] (the stock
 * firmware's PServerBinder service — no root required).
 *
 * The methods are shaped around what PServer can honor: it cannot report an exit code and returns
 * only the first line of stdout, so callers must not rely on either. Use [exec] when the result is
 * irrelevant, [read] (which stages full output through a file) when stdout is needed, and
 * [launchDaemon] to start the long-running mirror. Success/failure that used to be an exit code
 * must become a token in the command's stdout.
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

/**
 * No-op backend reported as unavailable. Used as the default when no real shell is injected (the
 * graph always injects [PServerShell]); keeps repositories constructible in tests.
 */
internal object UnavailableShell : MirrorShell {
    override val isAvailable: Boolean = false
    override fun exec(command: String) {}
    override fun read(command: String): String = ""
    override fun launchDaemon(command: String) {}
}
