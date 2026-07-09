package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.ControllerDevice

interface InputDeviceRepository {
    fun getConnectedControllers(): List<ControllerDevice>
}
