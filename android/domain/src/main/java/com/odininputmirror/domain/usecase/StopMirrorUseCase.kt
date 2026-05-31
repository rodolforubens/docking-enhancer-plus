package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class StopMirrorUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke() {
        mirrorProcessRepository.stop()
        mirrorProcessRepository.clearProcessFiles()
        mirrorSettingsRepository.setExpectedRunning(false)
    }
}
