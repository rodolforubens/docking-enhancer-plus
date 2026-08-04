package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.TargetTraits

interface InputDeviceRepository {
    fun getConnectedControllers(): List<ControllerDevice>

    /**
     * What the mirror's target — the internal pad — declares it can receive, read from the live
     * device. Null when there is no internal pad to inspect; callers fall back to the measured Odin
     * defaults rather than to an empty editor.
     */
    fun targetTraits(): TargetTraits? = null
}
