package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.model.AutoMirrorTrigger
import com.odininputmirror.domain.model.ControllerGesture
import com.odininputmirror.domain.model.GestureAction
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
            homeSinglePressAction = readAction(KEY_HOME_SINGLE_PRESS_ACTION, GestureAction.HOME),
            homeDoublePressAction = readAction(KEY_HOME_DOUBLE_PRESS_ACTION, GestureAction.BACK),
            homeHoldAction = readAction(KEY_HOME_HOLD_ACTION, GestureAction.RECENTS),
            selectStartHoldAction = readAction(KEY_SELECT_START_HOLD_ACTION, GestureAction.CLOSE_APP),
            selectR3HoldAction = readAction(KEY_SELECT_R3_HOLD_ACTION, GestureAction.TOGGLE_VIRTUAL_MOUSE),
            autoMirrorEnabled = prefs.getBoolean(KEY_AUTO_MIRROR_ENABLED, true),
            autoMirrorTrigger = prefs.getString(KEY_AUTO_MIRROR_TRIGGER, null)
                ?.let { stored -> AutoMirrorTrigger.entries.firstOrNull { it.name == stored } }
                ?: AutoMirrorTrigger.CONTROLLER_AND_DISPLAY,
            expectedRunning = prefs.getBoolean(KEY_EXPECTED_RUNNING, false),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            manualInternalGuid = prefs.getString(KEY_MANUAL_INTERNAL_GUID, null),
            manualExternalGuid = prefs.getString(KEY_MANUAL_EXTERNAL_GUID, null),
            configGeneration = prefs.getLong(KEY_CONFIG_GENERATION, 0L),
        )
    }

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        prefs.edit()
            .putString(KEY_SOURCE, request.source)
            .putString(KEY_TARGET, request.target)
            .putString(KEY_SOURCE_GUID, request.sourceGuid)
            .putString(KEY_TARGET_GUID, request.targetGuid)
            .putString(KEY_HOME_SINGLE_PRESS_ACTION, request.homeSinglePressAction.name)
            .putString(KEY_HOME_DOUBLE_PRESS_ACTION, request.homeDoublePressAction.name)
            .putString(KEY_HOME_HOLD_ACTION, request.homeHoldAction.name)
            .putString(KEY_SELECT_START_HOLD_ACTION, request.selectStartHoldAction.name)
            .putString(KEY_SELECT_R3_HOLD_ACTION, request.selectR3HoldAction.name)
            .putBoolean(KEY_EXPECTED_RUNNING, true)
            .putLong(KEY_STARTED_AT, startedAt)
            .apply()
    }

    override fun setExpectedRunning(expectedRunning: Boolean) {
        prefs.edit().putBoolean(KEY_EXPECTED_RUNNING, expectedRunning).apply()
    }

    override fun setGestureAction(gesture: ControllerGesture, action: GestureAction) {
        val key = when (gesture) {
            ControllerGesture.HOME_SINGLE_PRESS -> KEY_HOME_SINGLE_PRESS_ACTION
            ControllerGesture.HOME_DOUBLE_PRESS -> KEY_HOME_DOUBLE_PRESS_ACTION
            ControllerGesture.HOME_HOLD -> KEY_HOME_HOLD_ACTION
            ControllerGesture.SELECT_START_HOLD -> KEY_SELECT_START_HOLD_ACTION
            ControllerGesture.SELECT_R3_HOLD -> KEY_SELECT_R3_HOLD_ACTION
        }
        setLiveAction(key, action)
    }

    // Options the running daemon can adopt without being restarted. The generation advances in the
    // same commit as the value: it is the number the daemon acknowledges once it has adopted the
    // change, so a value stored without advancing it would be a change nobody ever asks it to make.
    override fun bumpConfigGeneration() = synchronized(generationLock) {
        prefs.edit().putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1).apply()
    }

    private fun setLiveAction(key: String, action: GestureAction) = synchronized(generationLock) {
        prefs.edit()
            .putString(key, action.name)
            .putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1)
            .apply()
    }

    private fun readAction(key: String, default: GestureAction): GestureAction =
        prefs.getString(key, null)
            ?.let { stored -> GestureAction.entries.firstOrNull { it.name == stored } }
            ?: default

    private companion object {
        /*
         * The generation is read-modify-written, and every writer runs on its own IO coroutine: two
         * assignments changed in quick succession both read N and both write N+1, so the supervisor sees
         * the number it already pushed and the second change never reaches the daemon. It reads as a
         * setting that silently did nothing.
         *
         * Held on the companion rather than the instance because the settings are one file and
         * nothing guarantees one repository object per process — two instances synchronizing on
         * themselves would take two different locks and protect nothing.
         */
        val generationLock = Any()
    }

    override fun setAutoMirrorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MIRROR_ENABLED, enabled).apply()
    }

    override fun setAutoMirrorTrigger(trigger: AutoMirrorTrigger) {
        prefs.edit().putString(KEY_AUTO_MIRROR_TRIGGER, trigger.name).apply()
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

    override fun setManualExternalController(guid: String?) {
        prefs.edit().apply {
            if (guid.isNullOrBlank()) {
                remove(KEY_MANUAL_EXTERNAL_GUID)
            } else {
                putString(KEY_MANUAL_EXTERNAL_GUID, guid)
            }
        }.apply()
    }
}
