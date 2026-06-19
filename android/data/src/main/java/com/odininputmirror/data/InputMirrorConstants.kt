package com.odininputmirror.data

import android.view.KeyEvent
import android.view.MotionEvent

internal val CONTROLLER_BUTTONS = intArrayOf(
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_THUMBL,
    KeyEvent.KEYCODE_BUTTON_THUMBR,
    KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

internal val CONTROLLER_AXES = intArrayOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RX,
    MotionEvent.AXIS_RY,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_LTRIGGER,
    MotionEvent.AXIS_RTRIGGER,
)

internal const val BUS_USB = 0x0003
internal const val BUS_BLUETOOTH = 0x0005
internal const val ODIN_VENDOR_ID = 0x2020
internal const val ODIN_INTERNAL_CONTROLLER_PRODUCT_ID = 0x0112

internal const val PREFS = "input_mirror"
internal const val KEY_SOURCE = "source"
internal const val KEY_TARGET = "target"
internal const val KEY_SOURCE_GUID = "source_guid"
internal const val KEY_TARGET_GUID = "target_guid"
internal const val KEY_HOME_AS_BACK = "home_as_back"
internal const val KEY_COMBO_HOLD_KILL_APP = "combo_hold_kill_app"
internal const val KEY_AUTO_RESTART = "auto_restart"
internal const val KEY_AUTO_MIRROR_ENABLED = "auto_mirror_enabled"
internal const val KEY_EXPECTED_RUNNING = "expected_running"
internal const val KEY_STARTED_AT = "started_at"

internal const val STARTING_GRACE_MS = 1500L
internal const val HEARTBEAT_STALE_MS = 5000L
internal const val IS_MIRROR_RUNNING_COMMAND =
    "for pid in \$(pidof input_mirror 2>/dev/null); do state=\$(cat /proc/\$pid/stat 2>/dev/null | awk '{print \$3}'); [ \"\$state\" != \"Z\" ] && exit 0; done; exit 1"
internal const val STOP_MIRROR_COMMAND =
    "pids=\$(pidof input_mirror 2>/dev/null); [ -z \"\$pids\" ] && exit 0; kill -TERM \$pids 2>/dev/null; sleep 0.15; for pid in \$pids; do [ -d /proc/\$pid ] && kill -KILL \$pid 2>/dev/null; done; exit 0"
