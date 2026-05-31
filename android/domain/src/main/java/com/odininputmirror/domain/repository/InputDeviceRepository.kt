package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.ControllerDevice

interface InputDeviceRepository {
    fun getConnectedControllers(): List<ControllerDevice>
    fun findSavedDevice(path: String?, guid: String?, devices: List<ControllerDevice>): ControllerDevice?
}
