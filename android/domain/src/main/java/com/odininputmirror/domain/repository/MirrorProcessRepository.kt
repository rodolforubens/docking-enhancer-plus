package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.MirrorStartRequest

interface MirrorProcessRepository {
    /**
     * Launch the mirror daemon. Throws when the privileged backend could not even deliver the
     * launch command (so callers can surface/back off a failed start); a delivered launch that
     * then dies is detected later via [isRunning].
     */
    fun start(request: MirrorStartRequest)

    /** Stop the mirror daemon. Throws when the backend could not deliver the stop command. */
    fun stop()
    fun isRunning(): Boolean
    fun clearProcessFiles()
}
