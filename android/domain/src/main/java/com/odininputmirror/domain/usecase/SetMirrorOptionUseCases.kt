package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.repository.MirrorSettingsRepository

class SetHomeAsBackEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) = mirrorSettingsRepository.setHomeAsBackEnabled(enabled)
}

class SetComboHoldKillAppEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) = mirrorSettingsRepository.setComboHoldKillAppEnabled(enabled)
}

class SetVirtualMouseEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) = mirrorSettingsRepository.setVirtualMouseEnabled(enabled)
}

class SetAutoRestartEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) = mirrorSettingsRepository.setAutoRestartEnabled(enabled)
}
