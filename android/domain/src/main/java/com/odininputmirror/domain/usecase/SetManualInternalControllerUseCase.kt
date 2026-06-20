package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.repository.MirrorSettingsRepository

class SetManualInternalControllerUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(guid: String?) {
        mirrorSettingsRepository.setManualInternalController(guid)
    }
}
