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
        prefs.edit().remove(keyOf(key)).apply()
    }

    // Namespaced so a controller's mapping can never collide with the mirror's own settings, which
    // share this preferences file.
    private fun keyOf(key: MappingKey) = "$KEY_MAPPING_PREFIX${key.value}"
}
