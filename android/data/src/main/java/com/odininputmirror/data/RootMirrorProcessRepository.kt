package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import java.io.File

internal class RootMirrorProcessRepository(
    context: Context,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
    private val shell: MirrorShell = UnavailableShell,
) : MirrorProcessRepository {
    private val files = InputMirrorFiles(context.applicationContext)

    override fun start(request: MirrorStartRequest) {
        val binary = files.ensureBinaryInstalled()
        files.prepareProcessFiles()

        val homeAsBackArg = if (request.homeAsBack) " --home-as-back" else ""
        val comboHoldKillAppArg = if (request.comboHoldKillApp) " --combo-hold-kill-app" else ""
        val pidFileArg = " --pid-file ${files.pidFile.absolutePath.shellQuote()}"
        val heartbeatFileArg = " --heartbeat-file ${files.heartbeatFile.absolutePath.shellQuote()}"
        // Foreground invocation only; launchDaemon backgrounds it appropriately per backend. The
        // binary writes (and chmods) its own pid/heartbeat files via --pid-file/--heartbeat-file,
        // so no `echo $! > pidfile` bookkeeping is needed here.
        val command =
            "nice -n -20 ${binary.absolutePath.shellQuote()} ${request.source.shellQuote()} ${request.target.shellQuote()}$homeAsBackArg$comboHoldKillAppArg$pidFileArg$heartbeatFileArg"
        shell.launchDaemon(command)
    }

    override fun stop() {
        shell.exec(STOP_MIRROR_COMMAND)
    }

    override fun isRunning(): Boolean {
        val settings = mirrorSettingsRepository.getSettings()
        val startingGrace = settings.expectedRunning &&
            System.currentTimeMillis() - settings.startedAt < STARTING_GRACE_MS
        if (isHeartbeatFresh()) {
            return true
        }
        if (!startingGrace) {
            return false
        }

        val pid = runCatching { files.pidFile.readText().trim().toIntOrNull() }.getOrNull()
            ?: return startingGrace
        val procDir = File("/proc/$pid")
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

    override fun isRunningVerified(): Boolean {
        return runCatching {
            shell.read(IS_MIRROR_RUNNING_COMMAND).contains("RUNNING")
        }.getOrDefault(false)
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

        return System.currentTimeMillis() - heartbeatFile.lastModified() <= HEARTBEAT_STALE_MS
    }

    private fun readProcessState(pid: Int): String? {
        return runCatching {
            val stat = File("/proc/$pid/stat").readText()
            val stateStart = stat.lastIndexOf(") ") + 2
            stat.substring(stateStart).trim().split(Regex("\\s+")).firstOrNull()
        }.getOrNull()
    }
}
