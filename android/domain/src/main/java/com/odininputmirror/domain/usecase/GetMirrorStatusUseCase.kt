package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.MirrorStatus
import com.odininputmirror.domain.repository.DockStateRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class GetMirrorStatusUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
    private val dockStateRepository: DockStateRepository,
) {
    operator fun invoke(): MirrorStatus {
        val running = mirrorProcessRepository.isRunning()
        val settings = mirrorSettingsRepository.getSettings()
        return MirrorStatus(
            running = running,
            expectedRunning = settings.expectedRunning,
            source = settings.source,
            target = settings.target,
            sourceGuid = settings.sourceGuid,
            targetGuid = settings.targetGuid,
            homeSinglePressAction = settings.homeSinglePressAction,
            homeDoublePressAction = settings.homeDoublePressAction,
            homeHoldAction = settings.homeHoldAction,
            selectStartHoldAction = settings.selectStartHoldAction,
            selectR3HoldAction = settings.selectR3HoldAction,
            autoMirrorEnabled = settings.autoMirrorEnabled,
            autoMirrorTrigger = settings.autoMirrorTrigger,
            docked = dockStateRepository.isDockActive(),
            manualInternalGuid = settings.manualInternalGuid,
            manualExternalGuid = settings.manualExternalGuid,
        )
    }
}
