package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.MirrorStartRequest

interface MirrorProcessRepository {
    fun start(request: MirrorStartRequest)
    fun stop()
    fun isRunning(): Boolean
    fun isRunningVerified(): Boolean
    fun clearProcessFiles()
}
