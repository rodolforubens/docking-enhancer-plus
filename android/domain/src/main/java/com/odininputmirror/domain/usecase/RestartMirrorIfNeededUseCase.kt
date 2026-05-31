package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class RestartMirrorIfNeededUseCase(
    private val inputDeviceRepository: InputDeviceRepository,
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(restartAllowed: Boolean = true): RestartDecision {
        val settings = mirrorSettingsRepository.getSettings()
        if (!settings.autoRestart) {
            return RestartDecision.StopSupervisor
        }
        if (!settings.expectedRunning) {
            return RestartDecision.Idle
        }
        if (mirrorProcessRepository.isRunning()) {
            return RestartDecision.Running
        }

        mirrorProcessRepository.clearProcessFiles()
        val devices = inputDeviceRepository.getConnectedControllers()
        val source = inputDeviceRepository.findSavedDevice(settings.source, settings.sourceGuid, devices)
        val target = inputDeviceRepository.findSavedDevice(settings.target, settings.targetGuid, devices)

        if (source == null || target == null || source.path == target.path) {
            return RestartDecision.WaitingForDevice
        }
        if (!restartAllowed) {
            return RestartDecision.WaitingForDevice
        }

        mirrorProcessRepository.start(
            MirrorStartRequest(
                source = source.path,
                target = target.path,
                homeAsBack = settings.homeAsBack,
                comboHoldKillApp = settings.comboHoldKillApp,
                sourceGuid = source.guid,
                targetGuid = target.guid,
            )
        )
        mirrorSettingsRepository.saveRestarted(source.path, target.path, source.guid, target.guid)
        return RestartDecision.Restarted
    }
}

enum class RestartDecision {
    StopSupervisor,
    Idle,
    Running,
    WaitingForDevice,
    Restarted,
}
