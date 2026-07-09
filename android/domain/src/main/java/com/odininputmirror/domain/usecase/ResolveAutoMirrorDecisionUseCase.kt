package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest

class ResolveAutoMirrorDecisionUseCase {
    operator fun invoke(
        dockActive: Boolean,
        devices: List<ControllerDevice>,
        settings: MirrorSettings,
        mirrorRunning: Boolean,
        restartAllowed: Boolean = true,
    ): AutoMirrorDecision {
        if (!dockActive) {
            return AutoMirrorDecision.StopForDock
        }

        val local = devices.firstOrNull { it.isInternal }
            ?: return AutoMirrorDecision.WaitingForInternalController
        val external = devices.firstOrNull { !it.isInternal && !it.isSamePhysicalControllerAs(local) }
            ?: return AutoMirrorDecision.WaitingForExternalController

        if (local.path == external.path) {
            return AutoMirrorDecision.WaitingForExternalController
        }

        val request = MirrorStartRequest(
            source = external.path,
            target = local.path,
            sourceGuid = external.guid,
            targetGuid = local.guid,
            homeAsBack = settings.homeAsBack,
            comboHoldKillApp = settings.comboHoldKillApp,
            virtualMouse = settings.virtualMouse,
        )

        val sameMirror = settings.sourceGuid == external.guid &&
            settings.targetGuid == local.guid &&
            settings.source == external.path &&
            settings.target == local.path
        // The daemon reads its option flags once at launch; if a toggle changed them since, the
        // running mirror is stale and must be restarted for the new flags to take effect.
        val sameFlags = settings.startedHomeAsBack == settings.homeAsBack &&
            settings.startedComboHoldKillApp == settings.comboHoldKillApp &&
            settings.startedVirtualMouse == settings.virtualMouse
        if (mirrorRunning && sameMirror && sameFlags) {
            return AutoMirrorDecision.Running
        }
        if (!restartAllowed) {
            return AutoMirrorDecision.WaitingForRestartThrottle
        }

        return if (mirrorRunning) {
            AutoMirrorDecision.Restart(request)
        } else {
            AutoMirrorDecision.Start(request)
        }
    }
}

private fun ControllerDevice.isSamePhysicalControllerAs(other: ControllerDevice): Boolean {
    return controllerNumber > 0 && controllerNumber == other.controllerNumber
}

sealed class AutoMirrorDecision {
    object StopForDock : AutoMirrorDecision()
    object WaitingForInternalController : AutoMirrorDecision()
    object WaitingForExternalController : AutoMirrorDecision()
    object WaitingForRestartThrottle : AutoMirrorDecision()
    object Running : AutoMirrorDecision()
    data class Start(val request: MirrorStartRequest) : AutoMirrorDecision()
    data class Restart(val request: MirrorStartRequest) : AutoMirrorDecision()
}
