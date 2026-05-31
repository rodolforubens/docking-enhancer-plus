package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.MirrorStatus
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class GetMirrorStatusUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(verifyWithRoot: Boolean = false): MirrorStatus {
        val running = if (verifyWithRoot) {
            val verified = mirrorProcessRepository.isRunningVerified()
            if (!verified) {
                mirrorProcessRepository.clearProcessFiles()
            }
            verified
        } else {
            mirrorProcessRepository.isRunning()
        }
        val settings = mirrorSettingsRepository.getSettings()
        return MirrorStatus(
            running = running,
            expectedRunning = settings.expectedRunning,
            source = settings.source,
            target = settings.target,
            sourceGuid = settings.sourceGuid,
            targetGuid = settings.targetGuid,
            homeAsBack = settings.homeAsBack,
            comboHoldKillApp = settings.comboHoldKillApp,
            autoRestart = settings.autoRestart,
        )
    }
}
