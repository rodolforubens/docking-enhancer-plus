package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.AutoMirrorTrigger
import com.odininputmirror.domain.model.ControllerGesture
import com.odininputmirror.domain.model.GestureAction
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest

interface MirrorSettingsRepository {
    fun getSettings(): MirrorSettings
    fun saveStarted(request: MirrorStartRequest, startedAt: Long)
    fun setExpectedRunning(expectedRunning: Boolean)
    fun setGestureAction(gesture: ControllerGesture, action: GestureAction)
    fun setAutoMirrorEnabled(enabled: Boolean)
    fun setAutoMirrorTrigger(trigger: AutoMirrorTrigger)
    fun setManualInternalController(guid: String?)
    fun setManualExternalController(guid: String?)

    /**
     * Advance the config generation without changing a setting.
     *
     * Saving a mapping has to reach the running daemon, and the generation is what makes it look:
     * the supervisor pushes the config when the number it sees differs from the one stored here.
     */
    fun bumpConfigGeneration()
}
