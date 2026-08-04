package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.repository.DockStateRepository
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MappingRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import com.odininputmirror.domain.usecase.GetConnectedDevicesUseCase
import com.odininputmirror.domain.usecase.GetMirrorStatusUseCase
import com.odininputmirror.domain.usecase.ResolveAutoMirrorDecisionUseCase
import com.odininputmirror.domain.usecase.SetAutoMirrorEnabledUseCase
import com.odininputmirror.domain.usecase.SetComboHoldKillAppEnabledUseCase
import com.odininputmirror.domain.usecase.SetHomeAsBackEnabledUseCase
import com.odininputmirror.domain.usecase.SetManualInternalControllerUseCase
import com.odininputmirror.domain.usecase.SetVirtualMouseEnabledUseCase
import com.odininputmirror.domain.usecase.StartMirrorUseCase
import com.odininputmirror.domain.usecase.StopMirrorUseCase

class InputMirrorGraph(context: Context, forceDockMode: Boolean = false) {
    private val appContext = context.applicationContext

    // The mirror runs entirely through the stock firmware's PServerBinder service (no root). On a
    // device that doesn't ship it, [isSupportedDevice] is false and the app surfaces that instead
    // of running.
    private val shell: MirrorShell = PServerShell(appContext)

    /** True when this device ships the PServerBinder service the mirror needs. */
    val isSupportedDevice: Boolean get() = shell.isAvailable

    /** True when the firmware publishes PServerBinder but it does not answer (Odin 2 Mini). */
    val isServiceUnresponsive: Boolean get() = shell.isRegisteredButUnresponsive

    val settingsRepository: MirrorSettingsRepository = AndroidMirrorSettingsRepository(appContext)
    val dockStateRepository: DockStateRepository = AndroidDisplayDockStateRepository(appContext, forceDockMode)
    val processRepository: MirrorProcessRepository = RootMirrorProcessRepository(
        mirrorSettingsRepository = settingsRepository,
        files = InputMirrorFiles(appContext),
        shell = shell,
    )
    val mappingRepository: MappingRepository = AndroidMappingRepository(appContext)
    val inputDeviceRepository: InputDeviceRepository = AndroidInputDeviceRepository(
        shell = shell,
        manualInternalGuidProvider = { settingsRepository.getSettings().manualInternalGuid },
        hiddenSourcePathProvider = { settingsRepository.getSettings().source },
        mirrorRunningProvider = { processRepository.isRunning() },
    )

    // First-time defaults from the bundled SDL_GameControllerDB (see third-party/gamecontrollerdb).
    // The asset is re-read per seeding because seeding happens at most once per new controller.
    internal val defaultMappingSeeder = DefaultMappingSeeder(
        databaseLines = {
            appContext.assets.open("gamecontrollerdb.txt").bufferedReader().readLines().asSequence()
        },
        devices = inputDeviceRepository,
        mappings = mappingRepository,
    )

    /** Seed a known controller's default mapping if the user has never mapped it. Safe to repeat. */
    fun seedDefaultMapping(key: com.odininputmirror.domain.model.MappingKey) {
        runCatching { defaultMappingSeeder.seedIfEmpty(key) }
    }

    val getConnectedDevices = GetConnectedDevicesUseCase(inputDeviceRepository)
    val startMirror = StartMirrorUseCase(processRepository, settingsRepository)
    val stopMirror = StopMirrorUseCase(processRepository, settingsRepository)
    val getMirrorStatus = GetMirrorStatusUseCase(processRepository, settingsRepository, dockStateRepository)
    val setHomeAsBackEnabled = SetHomeAsBackEnabledUseCase(settingsRepository)
    val setComboHoldKillAppEnabled = SetComboHoldKillAppEnabledUseCase(settingsRepository)
    val setVirtualMouseEnabled = SetVirtualMouseEnabledUseCase(settingsRepository)
    val setAutoMirrorEnabled = SetAutoMirrorEnabledUseCase(settingsRepository)
    val setManualInternalController = SetManualInternalControllerUseCase(settingsRepository)
    val resolveAutoMirrorDecision = ResolveAutoMirrorDecisionUseCase()
}

/**
 * True when this device ships the PServerBinder service the mirror needs (no root). Lets the app
 * module gate startup without constructing the full graph.
 */
fun isPServerSupported(): Boolean = PServerExec().isAvailable
