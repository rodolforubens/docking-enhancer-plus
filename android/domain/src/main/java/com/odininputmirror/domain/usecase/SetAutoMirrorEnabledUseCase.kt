package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.AutoMirrorTrigger
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class SetAutoMirrorEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) {
        mirrorSettingsRepository.setAutoMirrorEnabled(enabled)
    }
}

class SetAutoMirrorTriggerUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(trigger: AutoMirrorTrigger) {
        mirrorSettingsRepository.setAutoMirrorTrigger(trigger)
    }
}
