package com.odininputmirror.data

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
) : MirrorProcessRepository {

    override fun start(request: MirrorStartRequest) {
        val binary = files.ensureBinaryInstalled()
        files.prepareProcessFiles()

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
            "nice -n -20 ${binary.absolutePath.shellQuote()} ${request.source.shellQuote()} ${request.target.shellQuote()}$homeAsBackArg$comboHoldKillAppArg$virtualMouseArg$hideNodeArgs$hiddenStateArg$pidFileArg$heartbeatFileArg"
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
        shell.exec(
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
