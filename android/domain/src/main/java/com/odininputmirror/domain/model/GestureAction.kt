package com.odininputmirror.domain.model

enum class GestureAction(val nativeCode: Int) {
    NONE(0),
    HOME(1),
    BACK(2),
    RECENTS(3),
    CLOSE_APP(4),
    TOGGLE_VIRTUAL_MOUSE(5),
    SLEEP(6),
}

enum class ControllerGesture {
    HOME_SINGLE_PRESS,
    HOME_DOUBLE_PRESS,
    HOME_HOLD,
    SELECT_START_HOLD,
    SELECT_R3_HOLD,
}
