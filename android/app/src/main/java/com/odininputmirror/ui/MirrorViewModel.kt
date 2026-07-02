package com.odininputmirror.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.odininputmirror.InputMirrorSupervisorService
import com.odininputmirror.data.InputMirrorGraph
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MirrorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MirrorUiState(
    val devices: List<ControllerDevice> = emptyList(),
    val localDevice: ControllerDevice? = null,
    val externalDevice: ControllerDevice? = null,
    val enabled: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String = "",
    val homeAsBack: Boolean = false,
    val comboHoldKillApp: Boolean = false,
    val autoMirrorEnabled: Boolean = true,
    val restartWaiting: Boolean = false,
    val docked: Boolean = false,
    val manualInternalGuid: String? = null,
    val unsupported: Boolean = false,
) {
    // On a recognised handheld (e.g. Odin) the native layer flags the internal controller by
    // hardware signature and locks it. Otherwise the internal defaults to the first detected
    // controller and the user can re-pick it manually.
    val hasKnownInternalProfile: Boolean
        get() = devices.any { it.isKnownInternal }
}

class MirrorViewModel(private val appContext: Context) : ViewModel() {
    private val graph = InputMirrorGraph(appContext)

    private val _state = MutableStateFlow(MirrorUiState())
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    // Poll fast while a state change is imminent (waiting for a controller, or for the mirror to
    // start/stop), and back off once the mirror is running steadily — this avoids draining the
    // handheld battery with a constant 2s cadence.
    private val activePollMs = 2000L
    private val idlePollMs = 6000L

    // Only poll PServer while the screen is in the foreground: the background supervisor service
    // owns the mirror on its own, so a backgrounded UI polling the privileged service just wastes
    // battery and binder traffic for a view nobody is looking at.
    @Volatile
    private var foreground = true

    init {
        if (graph.isSupportedDevice) {
            startSupervisor()
            viewModelScope.launch {
                initialLoad()
                while (true) {
                    val current = _state.value
                    val delayMs = if (current.enabled && !current.restartWaiting) idlePollMs else activePollMs
                    kotlinx.coroutines.delay(delayMs)
                    if (foreground) {
                        refresh(verifyWithRoot = false)
                    }
                }
            }
        } else {
            _state.update { it.copy(loading = false, unsupported = true) }
        }
    }

    fun onResume() {
        foreground = true
        viewModelScope.launch { refresh(verifyWithRoot = false) }
    }

    fun onPause() {
        foreground = false
    }

    private suspend fun initialLoad() {
        try {
            val (devices, status) = withContext(Dispatchers.IO) {
                graph.getConnectedDevices() to graph.getMirrorStatus()
            }
            applyStatus(status, devices)
        } catch (error: Exception) {
            _state.update { it.copy(message = error.message ?: error.toString()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun refresh(verifyWithRoot: Boolean) {
        try {
            val (devices, status) = withContext(Dispatchers.IO) {
                graph.getConnectedDevices() to graph.getMirrorStatus(verifyWithRoot)
            }
            applyStatus(status, devices)
        } catch (_: Exception) {
            // Transient root/device failures are expected during reconnects; keep last state.
        }
    }

    private fun applyStatus(status: MirrorStatus, available: List<ControllerDevice>) {
        val autoLocal = available.firstOrNull { it.isInternal }
        val autoExternal = available.firstOrNull { !it.isInternal }
        val savedExternalIsLocal =
            autoLocal != null && savedIdentityMatches(status.source, status.sourceGuid, autoLocal)

        val localDevice = autoLocal
            ?: deviceFromSavedIdentity(status.target, status.targetGuid, available, "Saved Odin controller")
        val externalDevice = autoExternal
            ?: if (savedExternalIsLocal) {
                null
            } else {
                deviceFromSavedIdentity(status.source, status.sourceGuid, available, "Waiting for external controller")
            }

        _state.update {
            it.copy(
                devices = available,
                localDevice = localDevice,
                externalDevice = externalDevice,
                enabled = status.running,
                homeAsBack = status.homeAsBack,
                comboHoldKillApp = status.comboHoldKillApp,
                autoMirrorEnabled = status.autoMirrorEnabled,
                restartWaiting = status.expectedRunning && !status.running,
                docked = status.docked,
                manualInternalGuid = status.manualInternalGuid,
                message = buildStatusMessage(status, available),
            )
        }
    }

    fun toggleHomeAsBack(nextValue: Boolean) {
        val current = _state.value
        if (current.autoMirrorEnabled || current.busy) return
        _state.update { it.copy(homeAsBack = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setHomeAsBackEnabled(nextValue) }
            } catch (error: Exception) {
                _state.update { it.copy(homeAsBack = !nextValue, message = error.message ?: error.toString()) }
            }
        }
    }

    fun toggleComboHoldKillApp(nextValue: Boolean) {
        val current = _state.value
        if (current.autoMirrorEnabled || current.busy) return
        _state.update { it.copy(comboHoldKillApp = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setComboHoldKillAppEnabled(nextValue) }
            } catch (error: Exception) {
                _state.update { it.copy(comboHoldKillApp = !nextValue, message = error.message ?: error.toString()) }
            }
        }
    }

    fun toggleAutoMirrorEnabled(nextValue: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, autoMirrorEnabled = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    graph.setAutoMirrorEnabled(nextValue)
                    if (!nextValue) {
                        // Best-effort: the setting is already persisted and the supervisor also
                        // stops the mirror, so a stop hiccup (e.g. nothing was running) must not
                        // revert the toggle or surface an error to the user.
                        runCatching { graph.stopMirror() }
                    }
                }
                if (nextValue) startSupervisor()
                _state.update {
                    it.copy(
                        enabled = if (nextValue) it.enabled else false,
                        message = if (nextValue) "Automatic dock mirror is enabled." else "Automatic dock mirror is disabled.",
                    )
                }
            } catch (error: Exception) {
                _state.update { it.copy(autoMirrorEnabled = !nextValue, message = error.message ?: error.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun selectInternalController(guid: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setManualInternalController(guid) }
                _state.update { it.copy(manualInternalGuid = guid) }
                refresh(verifyWithRoot = false)
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: error.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun startSupervisor() {
        val intent = Intent(appContext, InputMirrorSupervisorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun buildStatusMessage(status: MirrorStatus, devices: List<ControllerDevice>): String = when {
        status.running -> "Dock mirror active."
        !status.autoMirrorEnabled -> "Automatic dock mirror is disabled."
        devices.none { it.isInternal } -> "Waiting for Odin internal controller."
        devices.none { !it.isInternal } -> "Waiting for an external controller."
        status.expectedRunning -> "Controller detected. Mirror will start automatically."
        else -> "Automatic dock mirror is ready."
    }

    private fun savedIdentityMatches(path: String?, guid: String?, device: ControllerDevice): Boolean =
        (guid != null && device.guid == guid) || (path != null && device.path == path)

    private fun findDeviceBySavedIdentity(
        path: String?,
        guid: String?,
        devices: List<ControllerDevice>,
    ): ControllerDevice? {
        if (!guid.isNullOrEmpty()) {
            devices.firstOrNull { it.guid == guid }?.let { return it }
        }
        if (!path.isNullOrEmpty()) {
            return devices.firstOrNull { it.path == path }
        }
        return null
    }

    private fun deviceFromSavedIdentity(
        path: String?,
        guid: String?,
        devices: List<ControllerDevice>,
        fallbackName: String,
    ): ControllerDevice? {
        findDeviceBySavedIdentity(path, guid, devices)?.let { return it }
        if (path.isNullOrEmpty() && guid.isNullOrEmpty()) return null
        return ControllerDevice(
            name = fallbackName,
            path = path ?: "",
            guid = guid ?: "",
            controllerNumber = 0,
        )
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MirrorViewModel(context.applicationContext) as T
        }
    }
}
