package com.odininputmirror.data

import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.mappingSlots
import com.odininputmirror.domain.model.odinFallbackTraits
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MappingRepository

/**
 * First-time defaults from the bundled controller database.
 *
 * A pad the database knows gets its mapping the moment it is first about to be mirrored, so it works
 * before the editor is ever opened. Seeding writes through [MappingRepository] like any user edit,
 * so the editor shows and adjusts it with nothing special-cased.
 *
 * A pad whose mapping the user emptied is indistinguishable from one never seen, so "Clear all" is
 * followed by a re-seed on the next mirror start. That is the intended meaning: clearing returns the
 * pad to its defaults, and for a known pad the database entry IS its default — a user who wants a
 * binding gone clears that one row, which leaves the mapping non-empty and therefore untouched.
 */
internal class DefaultMappingSeeder(
    private val databaseLines: () -> Sequence<String>,
    private val devices: InputDeviceRepository,
    private val mappings: MappingRepository,
) {
    fun seedIfEmpty(key: MappingKey) {
        val existing = runCatching { mappings.get(key) }.getOrNull() ?: return
        if (!existing.isEmpty) {
            return
        }

        val parts = key.value.split(':')
        val vendor = parts.getOrNull(0)?.toIntOrNull(16) ?: return
        val product = parts.getOrNull(1)?.toIntOrNull(16) ?: return

        val entry = GameControllerDb(databaseLines()).entryFor(vendor, product) ?: return
        // The REAL pad's capabilities: the entry's indices were assigned against that device, and
        // its /proc entry survives even while its /dev node is hidden.
        val source = devices.sourceTraits(key) ?: return
        val slots = mappingSlots(devices.targetTraits() ?: odinFallbackTraits())

        val mapping = GameControllerDb.toMapping(entry, source.keys, source.axes, slots)
        if (!mapping.isEmpty) {
            mappings.save(key, mapping)
            // Marked so the UI can tell a default apart from the user's work: the "custom mapping"
            // badge should only ever mean the user made it. The first edit clears the mark.
            mappings.setSeeded(key, true)
        }
    }
}
