package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.TargetTraits

interface InputDeviceRepository {
    fun getConnectedControllers(): List<ControllerDevice>

    /**
     * What the mirror's target — the internal pad — declares it can receive, read from the live
     * device. Null when there is no internal pad to inspect; callers fall back to the measured Odin
     * defaults rather than to an empty editor.
     */
    fun targetTraits(): TargetTraits? = null

    /**
     * The capability bitmasks of the REAL external controller behind this mapping key. Same shape as
     * the target's traits, no labels: what matters here is which codes the pad declares, because the
     * controller database's indices only mean anything counted over exactly that set.
     */
    fun sourceTraits(key: MappingKey): TargetTraits? = null
}
