package com.odininputmirror.domain.model

fun List<ControllerDevice>.findSavedControllerDevice(
    path: String?,
    guid: String?,
): ControllerDevice? {
    findControllerByGuid(guid)?.let { return it }

    if (!path.isNullOrBlank()) {
        find { it.path == path }?.let { return it }
    }

    return null
}

/**
 * Resolve a persisted device descriptor, accepting the old vendor:product-derived value only when
 * it identifies exactly one device. That uniqueness guard prevents a legacy Odin GUID shared by
 * several firmware twins from silently selecting the wrong controller.
 */
fun List<ControllerDevice>.findControllerByGuid(guid: String?): ControllerDevice? {
    if (guid.isNullOrBlank()) return null

    firstOrNull { it.guid == guid }?.let { return it }
    return filter { it.legacyGuid == guid }.singleOrNull()
}
