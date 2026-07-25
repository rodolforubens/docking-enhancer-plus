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

    // Whether the UI is currently on-screen. The supervisor skips its one privileged PServer call
    // per tick (the controller enumeration) when undocked AND the UI is hidden, since nobody reads
    // the device list then and the undocked auto-mirror decision ignores it — so a pocketed handheld
    // stops spawning a pserver subprocess every idle tick.
    @Volatile
    var uiVisible: Boolean = false
        private set

    private val tickMonitor = Object()

    /** Called from the Activity lifecycle. A hidden→visible flip wakes the supervisor so the device
     * list refreshes immediately instead of waiting out the current idle interval. */
    fun setUiVisible(visible: Boolean) {
        val wasVisible = uiVisible
        uiVisible = visible
        if (visible && !wasVisible) {
            synchronized(tickMonitor) { tickMonitor.notifyAll() }
        }
    }

    /** The supervisor sleeps here between ticks so [setUiVisible] can cut the wait short. Spurious
     * wakeups only cost one early tick, which is harmless. Propagates interrupts for clean shutdown. */
    fun awaitNextTick(maxSleepMs: Long) {
        synchronized(tickMonitor) { tickMonitor.wait(maxSleepMs) }
    }
}
