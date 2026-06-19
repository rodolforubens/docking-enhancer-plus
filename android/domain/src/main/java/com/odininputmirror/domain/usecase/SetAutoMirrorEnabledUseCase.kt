package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.repository.MirrorSettingsRepository

class SetAutoMirrorEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) {
        mirrorSettingsRepository.setAutoMirrorEnabled(enabled)
    }
}
