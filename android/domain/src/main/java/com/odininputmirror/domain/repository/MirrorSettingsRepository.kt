package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest

interface MirrorSettingsRepository {
    fun getSettings(): MirrorSettings
    fun saveStarted(request: MirrorStartRequest, startedAt: Long)
    fun saveRestarted(source: String, target: String, sourceGuid: String?, targetGuid: String?)
    fun setExpectedRunning(expectedRunning: Boolean)
    fun setHomeAsBackEnabled(enabled: Boolean)
    fun setComboHoldKillAppEnabled(enabled: Boolean)
    fun setAutoRestartEnabled(enabled: Boolean)
}
