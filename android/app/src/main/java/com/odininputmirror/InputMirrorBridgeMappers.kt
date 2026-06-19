package com.odininputmirror

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MirrorStatus

internal fun List<ControllerDevice>.toWritableArray(): WritableArray {
    val devices = Arguments.createArray()
    forEach { entry ->
        val handlers = Arguments.createArray()
        entry.handlers.forEach { handlers.pushString(it) }

        val device = Arguments.createMap()
        device.putString("name", entry.name)
        device.putString("path", entry.path)
        device.putString("guid", entry.guid)
        device.putInt("controllerNumber", entry.controllerNumber)
        device.putArray("handlers", handlers)
        device.putBoolean("isOdinInternal", entry.isOdinInternal)
        devices.pushMap(device)
    }
    return devices
}

internal fun MirrorStatus.toWritableMap(): WritableMap {
    val status = Arguments.createMap()
    status.putBoolean("running", running)
    status.putBoolean("expectedRunning", expectedRunning)
    status.putString("source", source)
    status.putString("target", target)
    status.putString("sourceGuid", sourceGuid)
    status.putString("targetGuid", targetGuid)
    status.putBoolean("homeAsBack", homeAsBack)
    status.putBoolean("comboHoldKillApp", comboHoldKillApp)
    status.putBoolean("autoRestart", autoRestart)
    status.putBoolean("autoMirrorEnabled", autoMirrorEnabled)
    return status
}
