package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.repository.MappingRepository

internal class AndroidMappingRepository(context: Context) : MappingRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: MappingKey): ControllerMapping = parseMapping(prefs.getString(keyOf(key), null))

    override fun save(key: MappingKey, mapping: ControllerMapping) {
        if (mapping.isEmpty) {
            // A wizard the user skipped their way through leaves nothing behind, rather than an
            // empty record that would read as "this controller has a custom mapping".
            clear(key)
            return
        }
        prefs.edit().putString(keyOf(key), mapping.serialize()).apply()
    }

    override fun clear(key: MappingKey) {
        // The flag goes with the mapping it describes: a cleared pad re-seeds as if never seen.
        prefs.edit().remove(keyOf(key)).remove(seededKeyOf(key)).apply()
    }

    override fun isSeeded(key: MappingKey): Boolean = prefs.getBoolean(seededKeyOf(key), false)

    override fun setSeeded(key: MappingKey, seeded: Boolean) {
        prefs.edit().apply {
            if (seeded) putBoolean(seededKeyOf(key), true) else remove(seededKeyOf(key))
        }.apply()
    }

    // Namespaced so a controller's mapping can never collide with the mirror's own settings, which
    // share this preferences file.
    private fun keyOf(key: MappingKey) = "$KEY_MAPPING_PREFIX${key.value}"

    private fun seededKeyOf(key: MappingKey) = "${KEY_MAPPING_PREFIX}seeded_${key.value}"
}
