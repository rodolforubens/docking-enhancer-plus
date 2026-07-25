package com.odininputmirror.domain.model

data class ControllerDevice(
    val name: String,
    val path: String,
    val guid: String,
    val controllerNumber: Int,
    val handlers: List<String> = emptyList(),
    // Effective internal flag used to pick the mirror target (signature, manual, or default).
    val isInternal: Boolean = false,
    // True only when matched by a recognised handheld signature (e.g. Odin); such a pick is
    // locked and not user-editable. Default/manual picks set isInternal but not this.
    val isKnownInternal: Boolean = false,
    // The /dev/input node the hide-external feature unlinks to remove THIS external controller from
    // the framework's device list — the Odin quirk (vendor 0x2020) node bearing this controller's
    // name. It may be a separate re-exposed twin OR the very node the mirror reads as source (when
    // the firmware exposes only the re-exposed node in /dev); either way it is the framework-visible
    // one. Null for the internal controller and when no such node exists.
    val hideNodePath: String? = null,
)
