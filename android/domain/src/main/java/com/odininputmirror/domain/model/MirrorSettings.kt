package com.odininputmirror.domain.model

data class MirrorSettings(
    val source: String? = null,
    val target: String? = null,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
    val homeAsBack: Boolean = false,
    val comboHoldKillApp: Boolean = false,
    val virtualMouse: Boolean = false,
    val autoRestart: Boolean = false,
    val autoMirrorEnabled: Boolean = true,
    val expectedRunning: Boolean = false,
    val startedAt: Long = 0L,
    val manualInternalGuid: String? = null,
)
