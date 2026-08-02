package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.MirrorSettings
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

    /**
     * Hand the live options to a running daemon without restarting it — which matters because a
     * restart has to release the exclusive grab, leaving the controller visible to the whole system
     * for about a second. Returns whether the request was delivered; the daemon confirms that it
     * actually adopted them through [appliedConfigGeneration].
     */
    fun applyLiveSettings(settings: MirrorSettings): Boolean

    /**
     * Generation of the config the running daemon has adopted, or null when it cannot be read — no
     * fresh heartbeat, or a daemon predating the ack. Null is what sends callers back to the restart
     * path instead of leaving a toggle silently unapplied.
     */
    fun appliedConfigGeneration(): Long?
}
