package com.odininputmirror

import android.content.Intent
import android.os.Build
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.odininputmirror.data.InputMirrorGraph
import com.odininputmirror.domain.model.MirrorStartRequest

class InputMirrorModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {
    private val graph = InputMirrorGraph(reactContext)

    init {
        startSupervisor()
    }

    override fun getName(): String = "InputMirror"

    @ReactMethod
    fun startMirror(
        source: String,
        target: String,
        homeAsBack: Boolean,
        comboHoldKillApp: Boolean,
        sourceGuid: String?,
        targetGuid: String?,
        promise: Promise,
    ) {
        try {
            graph.startMirror(
                MirrorStartRequest(
                    source = source,
                    target = target,
                    homeAsBack = homeAsBack,
                    comboHoldKillApp = comboHoldKillApp,
                    sourceGuid = sourceGuid,
                    targetGuid = targetGuid,
                )
            )
            startSupervisor()
            promise.resolve("started")
        } catch (error: Exception) {
            promise.reject("START_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun stopMirror(promise: Promise) {
        try {
            graph.stopMirror()
            stopSupervisor()
            promise.resolve("stopped")
        } catch (error: Exception) {
            promise.reject("STOP_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getConnectedDevices(promise: Promise) {
        try {
            promise.resolve(graph.getConnectedDevices().toWritableArray())
        } catch (error: Exception) {
            promise.reject("DEVICE_SCAN_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getMirrorStatus(promise: Promise) {
        try {
            promise.resolve(graph.getMirrorStatus().toWritableMap())
        } catch (error: Exception) {
            promise.reject("STATUS_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun getMirrorStatusVerified(promise: Promise) {
        try {
            promise.resolve(graph.getMirrorStatus(verifyWithRoot = true).toWritableMap())
        } catch (error: Exception) {
            promise.reject("STATUS_VERIFY_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun setHomeAsBackEnabled(enabled: Boolean, promise: Promise) {
        try {
            graph.setHomeAsBackEnabled(enabled)
            promise.resolve(enabled)
        } catch (error: Exception) {
            promise.reject("SAVE_SETTING_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun setComboHoldKillAppEnabled(enabled: Boolean, promise: Promise) {
        try {
            graph.setComboHoldKillAppEnabled(enabled)
            promise.resolve(enabled)
        } catch (error: Exception) {
            promise.reject("SAVE_SETTING_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun setAutoRestartEnabled(enabled: Boolean, promise: Promise) {
        try {
            graph.setAutoRestartEnabled(enabled)
            promise.resolve(enabled)
        } catch (error: Exception) {
            promise.reject("SAVE_SETTING_FAILED", error.message, error)
        }
    }

    @ReactMethod
    fun setAutoMirrorEnabled(enabled: Boolean, promise: Promise) {
        try {
            graph.setAutoMirrorEnabled(enabled)
            if (!enabled) {
                graph.stopMirror()
            } else {
                startSupervisor()
            }
            promise.resolve(enabled)
        } catch (error: Exception) {
            promise.reject("SAVE_SETTING_FAILED", error.message, error)
        }
    }

    private fun startSupervisor() {
        val intent = Intent(reactContext, InputMirrorSupervisorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    private fun stopSupervisor() {
        reactContext.stopService(Intent(reactContext, InputMirrorSupervisorService::class.java))
    }
}
