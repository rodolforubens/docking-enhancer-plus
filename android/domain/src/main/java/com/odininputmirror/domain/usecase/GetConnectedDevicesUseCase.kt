package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.repository.InputDeviceRepository

class GetConnectedDevicesUseCase(
    private val inputDeviceRepository: InputDeviceRepository,
) {
    operator fun invoke() = inputDeviceRepository.getConnectedControllers()
}
