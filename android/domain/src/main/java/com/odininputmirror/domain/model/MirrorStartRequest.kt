package com.odininputmirror.domain.model

data class MirrorStartRequest(
    val source: String,
    val target: String,
    val homeSinglePressAction: GestureAction = GestureAction.HOME,
    val homeDoublePressAction: GestureAction = GestureAction.BACK,
    val homeHoldAction: GestureAction = GestureAction.RECENTS,
    val selectStartHoldAction: GestureAction = GestureAction.CLOSE_APP,
    val selectR3HoldAction: GestureAction = GestureAction.TOGGLE_VIRTUAL_MOUSE,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
    // /dev/input nodes the daemon unlinks at startup (hiding them from the framework) and restores
    // on exit — the external controller's framework-visible Odin node. Empty when none was resolved.
    val hideNodes: List<String> = emptyList(),
    // The controller's captured mapping, resolved from its real identity by the caller. Empty means
    // "no correction", which is what every controller gets until someone runs the wizard.
    val mapping: ControllerMapping = ControllerMapping(),
)
