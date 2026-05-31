package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class StartMirrorUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    operator fun invoke(request: MirrorStartRequest) {
        mirrorProcessRepository.start(request)
        mirrorSettingsRepository.saveStarted(request, nowMillis())
    }
}
