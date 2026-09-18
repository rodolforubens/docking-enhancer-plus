package com.odininputmirror.domain.model

data class MirrorStatus(
    val running: Boolean,
    val expectedRunning: Boolean,
    val source: String?,
    val target: String?,
    val sourceGuid: String?,
    val targetGuid: String?,
    val homeSinglePressAction: GestureAction,
    val homeDoublePressAction: GestureAction,
    val homeHoldAction: GestureAction,
    val selectStartHoldAction: GestureAction,
    val selectR3HoldAction: GestureAction,
    val autoMirrorEnabled: Boolean,
    val autoMirrorTrigger: AutoMirrorTrigger,
    val docked: Boolean,
    val manualInternalGuid: String?,
    val manualExternalGuid: String? = null,
)
