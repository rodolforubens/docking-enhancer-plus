package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MappingKey

/**
 * Where a captured mapping lives between sessions.
 *
 * Keyed by the real controller rather than by the mirror source: this firmware republishes every
 * external pad under one shared vendor:product, so keying on what the mirror opens would hand the
 * second controller the first one's mapping.
 */
interface MappingRepository {
    /** The mapping saved for this controller, or an empty one when it has never been captured. */
    fun get(key: MappingKey): ControllerMapping

    fun save(key: MappingKey, mapping: ControllerMapping)

    /** Forget this controller's mapping, returning it to the daemon's defaults. */
    fun clear(key: MappingKey)
}
