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

    /**
     * Restore any /dev/input node a crashed or killed daemon left hidden (unlinked but never
     * restored). A cheap no-op when there is nothing to heal; call it when the mirror is not running
     * so a lingering hidden node can't keep the external controller invisible.
     */
    fun healOrphanedHideNodes()
}
