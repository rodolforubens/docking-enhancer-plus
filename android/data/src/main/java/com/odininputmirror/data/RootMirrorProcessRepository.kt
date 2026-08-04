package com.odininputmirror.data

import android.system.Os
import android.system.OsConstants
import com.odininputmirror.domain.model.CaptureKind
import com.odininputmirror.domain.model.CaptureRead
import com.odininputmirror.domain.model.CaptureResult
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import java.io.File

internal class RootMirrorProcessRepository(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
    private val files: InputMirrorFiles,
    private val shell: MirrorShell = UnavailableShell,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val procRoot: File = File("/proc"),
    // Injected like the shell above: android.system.Os is a stub on the JVM, and this path deserves
    // to stay unit-testable.
    private val sendCommand: (File, String) -> Boolean = ::writeControlCommand,
) : MirrorProcessRepository {

    override fun start(request: MirrorStartRequest) {
        val binary = files.ensureBinaryInstalled()
        files.prepareProcessFiles()

        // Hand the daemon its config up front so a fresh start and a hot reload read the same
        // document. The command-line flags below stay as the fallback the daemon uses when the file
        // is missing or unreadable — they are what the e2e harness drives it with.
        files.writeConfig(
            configJson(
                generation = mirrorSettingsRepository.getSettings().configGeneration,
                homeAsBack = request.homeAsBack,
                comboHoldKillApp = request.comboHoldKillApp,
                virtualMouse = request.virtualMouse,
                mapping = request.mapping,
            ),
        )
        files.ensureControlFifo()

        val configArg = " --config-file ${files.configFile.absolutePath.shellQuote()}"
        val controlFifoArg = " --control-fifo ${files.controlFifo.absolutePath.shellQuote()}"
        val captureArg = " --capture-file ${files.captureFile.absolutePath.shellQuote()}"
        val homeAsBackArg = if (request.homeAsBack) " --home-as-back" else ""
        val comboHoldKillAppArg = if (request.comboHoldKillApp) " --combo-hold-kill-app" else ""
        val virtualMouseArg = if (request.virtualMouse) " --virtual-mouse" else ""
        val hideNodeArgs = request.hideNodes.joinToString("") { " --hide-node ${it.shellQuote()}" }
        val hiddenStateArg = " --hidden-state-file ${files.hiddenStateFile.absolutePath.shellQuote()}"
        val pidFileArg = " --pid-file ${files.pidFile.absolutePath.shellQuote()}"
        val heartbeatFileArg = " --heartbeat-file ${files.heartbeatFile.absolutePath.shellQuote()}"
        // Foreground invocation only; launchDaemon backgrounds it appropriately per backend. The
        // binary writes (and chmods) its own pid/heartbeat files via --pid-file/--heartbeat-file,
        // so no `echo $! > pidfile` bookkeeping is needed here.
        //
        val launch =
            "nice -n -20 ${binary.absolutePath.shellQuote()} ${request.source.shellQuote()} ${request.target.shellQuote()}$homeAsBackArg$comboHoldKillAppArg$virtualMouseArg$hideNodeArgs$hiddenStateArg$configArg$controlFifoArg$captureArg$pidFileArg$heartbeatFileArg"

        // Only skip the launch for a daemon already mirroring THESE nodes.
        //
        // The old guard skipped for any live input_mirror at all, which turned a reconnect into a
        // dead mirror: the controller comes back as a different eventN, the supervisor asks for the
        // new one, and the guard sees the previous daemon — still alive, holding a file descriptor to
        // a node that no longer exists — and quietly does nothing. Nobody was wrong and nothing
        // worked. Matching on the argument list makes "already running" mean what it should, and
        // stops the stale one so its replacement can take the grab.
        val command = buildString {
            append("for p in \$(pidof input_mirror 2>/dev/null); do ")
            // The daemon's own argv, where $1 is the binary and $2/$3 are the source and target it
            // opened. Compared exactly rather than by pattern: a substring match would call a daemon
            // on /dev/input/event1 a match for a request for event10.
            append("set -- \$(tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null); ")
            append("[ \"\$2\" = ${request.source.shellQuote()} ] && ")
            append("[ \"\$3\" = ${request.target.shellQuote()} ] && exit 0; ")
            append("kill -TERM \$p 2>/dev/null; ")
            append("done; ")
            // Give a daemon we just signalled time to restore what it hid and drop its grab, or the
            // replacement races it for the EVIOCGRAB and loses.
            append("i=0; while pidof input_mirror >/dev/null 2>&1 && [ \$i -lt 20 ]; do sleep 0.1; i=\$((i+1)); done; ")
            append(launch)
        }
        if (!shell.launchDaemon(command)) {
            throw IllegalStateException("PServer could not deliver the mirror launch command")
        }
    }

    // Restore any node a crashed/killed daemon left hidden (its /dev node unlinked but never
    // restored), so the external controller reappears and the supervisor can start a fresh mirror.
    // Cheap no-op when there's nothing to heal — only reaches PServer when the state file is present
    // and non-empty. Runs the daemon in its [--heal] mode, which restores and clears the file.
    override fun healOrphanedHideNodes() {
        val stateFile = files.hiddenStateFile
        if (!stateFile.exists() || stateFile.length() == 0L) {
            return
        }
        val binary = files.ensureBinaryInstalled()
        // Never heal while a daemon is alive. isRunning() judges liveness by heartbeat freshness, so
        // a mirror starved of CPU for a few seconds reads as dead — healing on that would recreate
        // the very node it still has hidden and hand the framework a duplicate pad mid-session. The
        // daemon restores its own nodes on exit, so a live one is never the orphan we are after.
        shell.exec(
            "pidof input_mirror >/dev/null 2>&1 && exit 0; " +
                "${binary.absolutePath.shellQuote()} --heal --hidden-state-file ${stateFile.absolutePath.shellQuote()}"
        )
    }

    override fun stop() {
        if (!shell.exec(STOP_MIRROR_COMMAND)) {
            throw IllegalStateException("PServer could not deliver the mirror stop command")
        }
    }

    override fun isRunning(): Boolean {
        val settings = mirrorSettingsRepository.getSettings()
        val startingGrace = settings.expectedRunning &&
            nowMillis() - settings.startedAt < STARTING_GRACE_MS
        if (isHeartbeatFresh()) {
            return true
        }
        if (!startingGrace) {
            return false
        }

        val pid = runCatching { files.pidFile.readText().trim().toIntOrNull() }.getOrNull()
            ?: return startingGrace
        val procDir = File(procRoot, pid.toString())
        if (!procDir.exists()) {
            return startingGrace
        }

        val state = readProcessState(pid)
        if (state == "Z") {
            files.pidFile.delete()
            mirrorSettingsRepository.setExpectedRunning(false)
            return false
        }

        return true
    }

    override fun clearProcessFiles() {
        files.clearProcessFiles()
    }

    // Write the document first, then poke: the daemon only re-reads on the poke, so the file is
    // always complete by the time it looks. Both halves stay clear of PServer — the app owns the
    // config and the fifo — which is what keeps a toggle off the serialized transaction queue.
    override fun applyLiveSettings(settings: MirrorSettings, mapping: ControllerMapping): Boolean {
        files.writeConfig(
            configJson(
                generation = settings.configGeneration,
                homeAsBack = settings.homeAsBack,
                comboHoldKillApp = settings.comboHoldKillApp,
                virtualMouse = settings.virtualMouse,
                mapping = mapping,
            ),
        )
        return sendCommand(files.ensureControlFifo(), RELOAD_COMMAND)
    }

    override fun beginCapture(kind: CaptureKind): Boolean {
        val command = when (kind) {
            CaptureKind.BUTTON -> CAPTURE_BUTTON_COMMAND
            CaptureKind.STICK -> CAPTURE_STICK_COMMAND
        }
        return sendCommand(files.ensureControlFifo(), command)
    }

    override fun endCapture(): Boolean = sendCommand(files.ensureControlFifo(), CAPTURE_OFF_COMMAND)

    override fun clearCaptures() {
        files.captureFile.delete()
    }

    // The log is append-only and the caller carries the offset, so two presses in quick succession
    // both survive — which a single-value file the daemon rewrote would not manage.
    override fun readCaptures(offset: Long): CaptureRead {
        val file = files.captureFile
        val length = if (file.exists()) file.length() else 0L
        // A shorter file than we last read means it was cleared under us (a new wizard run); start
        // over rather than seeking past the end.
        val start = if (offset > length) 0L else offset
        if (length <= start) {
            return CaptureRead(offset = length)
        }

        val bytes = runCatching {
            file.inputStream().use { stream ->
                // position(), not skip(): skip is free to move fewer bytes than asked, and a short
                // one here would misalign this offset and every offset derived from it afterwards.
                stream.channel.position(start)
                stream.readBytes()
            }
        }.getOrNull() ?: return CaptureRead(offset = start)

        // Only whole lines: a line still being appended is picked up on the next read. The cut is
        // found in BYTES because that is what the offset counts — measuring the decoded string would
        // drift by one per multi-byte character the moment the log stopped being pure ASCII.
        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        if (lastNewline < 0) {
            return CaptureRead(offset = start)
        }

        val results = bytes.decodeToString(0, lastNewline)
            .lineSequence()
            .mapNotNull { parseCaptureLine(it) }
            .toList()
        // Advanced even when nothing parsed, so a line this build doesn't understand is stepped over
        // rather than re-read forever.
        return CaptureRead(results = results, offset = start + lastNewline + 1)
    }

    override fun appliedConfigGeneration(): Long? {
        if (!isHeartbeatFresh()) {
            return null
        }
        return runCatching {
            files.heartbeatFile.useLines { lines ->
                lines.firstOrNull { it.startsWith(GENERATION_PREFIX) }
                    ?.removePrefix(GENERATION_PREFIX)
                    ?.trim()
                    ?.toLongOrNull()
            }
        }.getOrNull()
    }

    private fun isHeartbeatFresh(): Boolean {
        val heartbeatFile = files.heartbeatFile
        // An empty file means the daemon touched nothing yet (or never started); treat it as not
        // running rather than trusting a fresh mtime.
        if (!heartbeatFile.exists() || heartbeatFile.length() == 0L) {
            return false
        }

        return nowMillis() - heartbeatFile.lastModified() <= HEARTBEAT_STALE_MS
    }

    private fun readProcessState(pid: Int): String? {
        return runCatching {
            val stat = File(procRoot, "$pid/stat").readText()
            val stateStart = stat.lastIndexOf(") ") + 2
            stat.substring(stateStart).trim().split(Regex("\\s+")).firstOrNull()
        }.getOrNull()
    }
}

// One line of the daemon's capture log. Unknown verbs are ignored rather than treated as an error,
// so an older app talking to a newer daemon skips what it doesn't understand instead of failing.
private fun parseCaptureLine(line: String): CaptureResult? {
    val parts = line.trim().split(' ').filter { it.isNotBlank() }
    return when {
        parts.size == 2 && parts[0] == "button" ->
            parts[1].toIntOrNull()?.let { CaptureResult.Button(it) }

        parts.size == 3 && parts[0] == "axis_button" -> {
            val code = parts[1].toIntOrNull()
            val sign = parts[2].toIntOrNull()
            if (code != null && sign != null) CaptureResult.AxisButton(code, sign) else null
        }

        parts.size == 5 && parts[0] == "stick" -> {
            val values = parts.drop(1).map { it.toIntOrNull() }
            if (values.any { it == null }) {
                null
            } else {
                CaptureResult.Stick(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
            }
        }

        else -> null
    }
}

// An empty binding table is written out rather than omitted: the daemon rebuilds its table from
// whatever the document names, so leaving the key out clears the mapping instead of keeping it.
//
// Every value here is a boolean or a number the app itself produced, so there is nothing to escape
// and no reason to pull in a JSON library for four fields. The daemon reads it with a scanner of
// matching simplicity.
private fun configJson(
    generation: Long,
    homeAsBack: Boolean,
    comboHoldKillApp: Boolean,
    virtualMouse: Boolean,
    mapping: ControllerMapping,
): String = """
    {
      "generation": $generation,
      "home_as_back": $homeAsBack,
      "combo_hold_kill_app": $comboHoldKillApp,
      "virtual_mouse": $virtualMouse,
      "mapping": {
        "bindings": [${mapping.configBindingRows()}]
      }
    }
""".trimIndent()

/**
 * Ask the daemon to re-read its config.
 *
 * O_NONBLOCK is the load-bearing part: opening a fifo for writing normally blocks until a reader
 * shows up, so a daemon that has died would hang whoever toggled a switch, forever. Non-blocking
 * turns "nobody is listening" into an immediate failure, which is exactly the signal the caller
 * needs to fall back to a restart.
 */
private fun writeControlCommand(fifo: File, command: String): Boolean {
    val descriptor = runCatching {
        Os.open(fifo.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
    }.getOrNull() ?: return false

    return try {
        // Well under PIPE_BUF, so the kernel delivers it as one indivisible chunk and the daemon
        // never reads half a command.
        val payload = command.toByteArray()
        Os.write(descriptor, payload, 0, payload.size)
        true
    } catch (_: Exception) {
        false
    } finally {
        runCatching { Os.close(descriptor) }
    }
}
