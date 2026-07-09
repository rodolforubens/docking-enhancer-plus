package com.odininputmirror.domain.model

data class MirrorSettings(
    val source: String? = null,
    val target: String? = null,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
    val homeAsBack: Boolean = false,
    val comboHoldKillApp: Boolean = false,
    val virtualMouse: Boolean = false,
    val autoMirrorEnabled: Boolean = true,
    val expectedRunning: Boolean = false,
    val startedAt: Long = 0L,
    val manualInternalGuid: String? = null,
    // Snapshot of the option flags the RUNNING daemon was launched with (persisted at start; the
    // daemon can outlive the app process). When a toggle changes homeAsBack/comboHoldKillApp/
    // virtualMouse afterwards, these diverge from the live values above and the auto-mirror
    // decision restarts the daemon so the new flags actually take effect.
    val startedHomeAsBack: Boolean = false,
    val startedComboHoldKillApp: Boolean = false,
    val startedVirtualMouse: Boolean = false,
)
