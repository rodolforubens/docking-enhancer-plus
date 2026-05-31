package com.odininputmirror.domain.model

data class MirrorStartRequest(
    val source: String,
    val target: String,
    val homeAsBack: Boolean,
    val comboHoldKillApp: Boolean,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
)
