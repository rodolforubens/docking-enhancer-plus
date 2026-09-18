package com.odininputmirror.domain.model

data class MirrorSettings(
    val source: String? = null,
    val target: String? = null,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
    val homeSinglePressAction: GestureAction = GestureAction.HOME,
    val homeDoublePressAction: GestureAction = GestureAction.BACK,
    val homeHoldAction: GestureAction = GestureAction.RECENTS,
    val selectStartHoldAction: GestureAction = GestureAction.CLOSE_APP,
    val selectR3HoldAction: GestureAction = GestureAction.TOGGLE_VIRTUAL_MOUSE,
    val autoMirrorEnabled: Boolean = true,
    val autoMirrorTrigger: AutoMirrorTrigger = AutoMirrorTrigger.CONTROLLER_AND_DISPLAY,
    val expectedRunning: Boolean = false,
    val startedAt: Long = 0L,
    val manualInternalGuid: String? = null,
    val manualExternalGuid: String? = null,
    // Advances every time a live option changes. The running daemon echoes the generation it has
    // actually adopted in its heartbeat, so comparing the two says whether a toggle has taken
    // effect — which is what lets a settings change reach the daemon without restarting it.
    val configGeneration: Long = 0L,
)
