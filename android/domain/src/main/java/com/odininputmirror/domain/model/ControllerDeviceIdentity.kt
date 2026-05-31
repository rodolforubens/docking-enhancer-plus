package com.odininputmirror.domain.model

fun List<ControllerDevice>.findSavedControllerDevice(
    path: String?,
    guid: String?,
): ControllerDevice? {
    if (!guid.isNullOrBlank()) {
        find { it.guid == guid }?.let { return it }
    }

    if (!path.isNullOrBlank()) {
        find { it.path == path }?.let { return it }
    }

    return null
}
