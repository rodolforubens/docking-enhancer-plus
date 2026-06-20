package com.odininputmirror.domain.model

data class MirrorStatus(
    val running: Boolean,
    val expectedRunning: Boolean,
    val source: String?,
    val target: String?,
    val sourceGuid: String?,
    val targetGuid: String?,
    val homeAsBack: Boolean,
    val comboHoldKillApp: Boolean,
    val autoRestart: Boolean,
    val autoMirrorEnabled: Boolean,
    val docked: Boolean,
    val manualInternalGuid: String?,
)
