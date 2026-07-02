package com.odininputmirror

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MirrorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the mirror snapshot, shared process-wide between the background
 * supervisor and the UI.
 *
 * The supervisor already polls the (privileged) PServer service every tick to drive the mirror;
 * it publishes what it finds here so the UI can simply observe it instead of running its own
 * parallel poll loop. That halves the redundant PServer traffic and keeps both views consistent.
 */
object MirrorStateStore {
    data class Snapshot(
        val devices: List<ControllerDevice>,
        val status: MirrorStatus,
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    fun publish(devices: List<ControllerDevice>, status: MirrorStatus) {
        _snapshot.value = Snapshot(devices, status)
    }
}
