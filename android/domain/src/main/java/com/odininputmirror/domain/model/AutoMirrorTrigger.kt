package com.odininputmirror.domain.model

enum class AutoMirrorTrigger(val requiresExternalDisplay: Boolean) {
    CONTROLLER_CONNECTED(requiresExternalDisplay = false),
    CONTROLLER_AND_DISPLAY(requiresExternalDisplay = true),
}
