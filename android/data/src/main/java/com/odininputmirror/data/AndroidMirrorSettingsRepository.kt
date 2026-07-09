package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class AndroidMirrorSettingsRepository(context: Context) : MirrorSettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun getSettings(): MirrorSettings {
        return MirrorSettings(
            source = prefs.getString(KEY_SOURCE, null),
            target = prefs.getString(KEY_TARGET, null),
            sourceGuid = prefs.getString(KEY_SOURCE_GUID, null),
            targetGuid = prefs.getString(KEY_TARGET_GUID, null),
            homeAsBack = prefs.getBoolean(KEY_HOME_AS_BACK, false),
            comboHoldKillApp = prefs.getBoolean(KEY_COMBO_HOLD_KILL_APP, false),
            virtualMouse = prefs.getBoolean(KEY_VIRTUAL_MOUSE, false),
            autoMirrorEnabled = prefs.getBoolean(KEY_AUTO_MIRROR_ENABLED, true),
            expectedRunning = prefs.getBoolean(KEY_EXPECTED_RUNNING, false),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            manualInternalGuid = prefs.getString(KEY_MANUAL_INTERNAL_GUID, null),
            startedHomeAsBack = prefs.getBoolean(KEY_STARTED_HOME_AS_BACK, false),
            startedComboHoldKillApp = prefs.getBoolean(KEY_STARTED_COMBO_HOLD_KILL_APP, false),
            startedVirtualMouse = prefs.getBoolean(KEY_STARTED_VIRTUAL_MOUSE, false),
        )
    }

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        prefs.edit()
            .putString(KEY_SOURCE, request.source)
            .putString(KEY_TARGET, request.target)
            .putString(KEY_SOURCE_GUID, request.sourceGuid)
            .putString(KEY_TARGET_GUID, request.targetGuid)
            .putBoolean(KEY_HOME_AS_BACK, request.homeAsBack)
            .putBoolean(KEY_COMBO_HOLD_KILL_APP, request.comboHoldKillApp)
            .putBoolean(KEY_VIRTUAL_MOUSE, request.virtualMouse)
            .putBoolean(KEY_STARTED_HOME_AS_BACK, request.homeAsBack)
            .putBoolean(KEY_STARTED_COMBO_HOLD_KILL_APP, request.comboHoldKillApp)
            .putBoolean(KEY_STARTED_VIRTUAL_MOUSE, request.virtualMouse)
            .putBoolean(KEY_EXPECTED_RUNNING, true)
            .putLong(KEY_STARTED_AT, startedAt)
            .apply()
    }

    override fun saveRestarted(source: String, target: String, sourceGuid: String?, targetGuid: String?) {
        prefs.edit()
            .putString(KEY_SOURCE, source)
            .putString(KEY_TARGET, target)
            .putString(KEY_SOURCE_GUID, sourceGuid)
            .putString(KEY_TARGET_GUID, targetGuid)
            .apply()
    }

    override fun setExpectedRunning(expectedRunning: Boolean) {
        prefs.edit().putBoolean(KEY_EXPECTED_RUNNING, expectedRunning).apply()
    }

    override fun setHomeAsBackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HOME_AS_BACK, enabled).apply()
    }

    override fun setComboHoldKillAppEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMBO_HOLD_KILL_APP, enabled).apply()
    }

    override fun setVirtualMouseEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIRTUAL_MOUSE, enabled).apply()
    }

    override fun setAutoMirrorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MIRROR_ENABLED, enabled).apply()
    }

    override fun setManualInternalController(guid: String?) {
        prefs.edit().apply {
            if (guid.isNullOrBlank()) {
                remove(KEY_MANUAL_INTERNAL_GUID)
            } else {
                putString(KEY_MANUAL_INTERNAL_GUID, guid)
            }
        }.apply()
    }
}
