package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.ControllerGesture
import com.odininputmirror.domain.model.GestureAction
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class SetGestureActionUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(gesture: ControllerGesture, action: GestureAction) =
        mirrorSettingsRepository.setGestureAction(gesture, action)
}
