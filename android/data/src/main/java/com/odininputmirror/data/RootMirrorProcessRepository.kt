package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import java.io.File

internal class RootMirrorProcessRepository(
    context: Context,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
    private val shell: Shell = Shell(),
) : MirrorProcessRepository {
    private val files = InputMirrorFiles(context.applicationContext)

    override fun start(request: MirrorStartRequest) {
        val binary = files.ensureBinaryInstalled()
        files.prepareProcessFiles()

        val homeAsBackArg = if (request.homeAsBack) " --home-as-back" else ""
        val comboHoldKillAppArg = if (request.comboHoldKillApp) " --combo-hold-kill-app" else ""
        val pidFileArg = " --pid-file ${files.pidFile.absolutePath.shellQuote()}"
        val heartbeatFileArg = " --heartbeat-file ${files.heartbeatFile.absolutePath.shellQuote()}"
        val command =
            "nice -n -20 ${binary.absolutePath.shellQuote()} ${request.source.shellQuote()} ${request.target.shellQuote()}$homeAsBackArg$comboHoldKillAppArg$pidFileArg$heartbeatFileArg >/dev/null 2>&1 & echo \$! > ${files.pidFile.absolutePath.shellQuote()}; chmod 666 ${files.pidFile.absolutePath.shellQuote()} ${files.heartbeatFile.absolutePath.shellQuote()}"
        shell.runSu(command)
    }

    override fun stop() {
        shell.runSu(STOP_MIRROR_COMMAND)
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
            shell.runSu(IS_MIRROR_RUNNING_COMMAND)
            true
        }.getOrDefault(false)
    }

    override fun clearProcessFiles() {
        files.clearProcessFiles()
    }

    private fun isHeartbeatFresh(): Boolean {
        val heartbeatFile = files.heartbeatFile
        if (!heartbeatFile.exists()) {
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
