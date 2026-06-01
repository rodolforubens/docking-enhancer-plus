package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class StopMirrorUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke() {
        try {
            mirrorProcessRepository.stop()
        } finally {
            mirrorProcessRepository.clearProcessFiles()
            mirrorSettingsRepository.setExpectedRunning(false)
        }
    }
}
