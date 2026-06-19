package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.repository.DockStateRepository
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import com.odininputmirror.domain.usecase.GetConnectedDevicesUseCase
import com.odininputmirror.domain.usecase.GetMirrorStatusUseCase
import com.odininputmirror.domain.usecase.RestartMirrorIfNeededUseCase
import com.odininputmirror.domain.usecase.ResolveAutoMirrorDecisionUseCase
import com.odininputmirror.domain.usecase.SetAutoRestartEnabledUseCase
import com.odininputmirror.domain.usecase.SetAutoMirrorEnabledUseCase
import com.odininputmirror.domain.usecase.SetComboHoldKillAppEnabledUseCase
import com.odininputmirror.domain.usecase.SetHomeAsBackEnabledUseCase
import com.odininputmirror.domain.usecase.StartMirrorUseCase
import com.odininputmirror.domain.usecase.StopMirrorUseCase

class InputMirrorGraph(context: Context, forceDockMode: Boolean = false) {
    private val appContext = context.applicationContext
    private val shell = Shell()

    val settingsRepository: MirrorSettingsRepository = AndroidMirrorSettingsRepository(appContext)
    val dockStateRepository: DockStateRepository = AndroidDisplayDockStateRepository(appContext, forceDockMode)
    val inputDeviceRepository: InputDeviceRepository = AndroidInputDeviceRepository(shell)
    val processRepository: MirrorProcessRepository = RootMirrorProcessRepository(
        context = appContext,
        mirrorSettingsRepository = settingsRepository,
        shell = shell,
    )

    val getConnectedDevices = GetConnectedDevicesUseCase(inputDeviceRepository)
    val startMirror = StartMirrorUseCase(processRepository, settingsRepository)
    val stopMirror = StopMirrorUseCase(processRepository, settingsRepository)
    val getMirrorStatus = GetMirrorStatusUseCase(processRepository, settingsRepository, dockStateRepository)
    val setHomeAsBackEnabled = SetHomeAsBackEnabledUseCase(settingsRepository)
    val setComboHoldKillAppEnabled = SetComboHoldKillAppEnabledUseCase(settingsRepository)
    val setAutoRestartEnabled = SetAutoRestartEnabledUseCase(settingsRepository)
    val setAutoMirrorEnabled = SetAutoMirrorEnabledUseCase(settingsRepository)
    val resolveAutoMirrorDecision = ResolveAutoMirrorDecisionUseCase()
    val restartMirrorIfNeeded = RestartMirrorIfNeededUseCase(
        inputDeviceRepository = inputDeviceRepository,
        mirrorProcessRepository = processRepository,
        mirrorSettingsRepository = settingsRepository,
    )
}
