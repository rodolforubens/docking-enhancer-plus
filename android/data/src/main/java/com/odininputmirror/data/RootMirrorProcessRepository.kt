package com.odininputmirror.data

import android.system.Os
import android.system.OsConstants
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
    private val sendReload: (File) -> Boolean = ::writeReloadCommand,
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
            ),
        )
        files.ensureControlFifo()

        val configArg = " --config-file ${files.configFile.absolutePath.shellQuote()}"
        val controlFifoArg = " --control-fifo ${files.controlFifo.absolutePath.shellQuote()}"
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
        // Guard against launching a duplicate: if an input_mirror is already alive (e.g. a daemon
        // that outlived a previous app session), bail out. The exclusive EVIOCGRAB would make the
        // second instance exit anyway, but this avoids the doomed spawn entirely.
        val launch =
            "nice -n -20 ${binary.absolutePath.shellQuote()} ${request.source.shellQuote()} ${request.target.shellQuote()}$homeAsBackArg$comboHoldKillAppArg$virtualMouseArg$hideNodeArgs$hiddenStateArg$configArg$controlFifoArg$pidFileArg$heartbeatFileArg"
        val command = "pidof input_mirror >/dev/null 2>&1 && exit 0; $launch"
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
    override fun applyLiveSettings(settings: MirrorSettings): Boolean {
        files.writeConfig(
            configJson(
                generation = settings.configGeneration,
                homeAsBack = settings.homeAsBack,
                comboHoldKillApp = settings.comboHoldKillApp,
                virtualMouse = settings.virtualMouse,
            ),
        )
        return sendReload(files.ensureControlFifo())
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

// Every value here is a boolean or a number the app itself produced, so there is nothing to escape
// and no reason to pull in a JSON library for four fields. The daemon reads it with a scanner of
// matching simplicity.
private fun configJson(
    generation: Long,
    homeAsBack: Boolean,
    comboHoldKillApp: Boolean,
    virtualMouse: Boolean,
): String = """
    {
      "generation": $generation,
      "home_as_back": $homeAsBack,
      "combo_hold_kill_app": $comboHoldKillApp,
      "virtual_mouse": $virtualMouse
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
private fun writeReloadCommand(fifo: File): Boolean {
    val descriptor = runCatching {
        Os.open(fifo.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
    }.getOrNull() ?: return false

    return try {
        // Well under PIPE_BUF, so the kernel delivers it as one indivisible chunk and the daemon
        // never reads half a command.
        val command = RELOAD_COMMAND.toByteArray()
        Os.write(descriptor, command, 0, command.size)
        true
    } catch (_: Exception) {
        false
    } finally {
        runCatching { Os.close(descriptor) }
    }
}
