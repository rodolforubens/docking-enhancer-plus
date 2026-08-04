package com.odininputmirror.data

import java.io.File

/**
 * Android's answer to "which button is this code": the device's key layout file.
 *
 * Android does not care what the kernel headers call a code — it maps the number through the `.kl`
 * resolved for the device's vendor:product, falling back to `Generic.kl`. On this firmware that file
 * says `key 0x133 BUTTON_X` and `key 0x134 BUTTON_Y`, the reverse of what the header names
 * (`BTN_NORTH`/`BTN_WEST`) suggest — reading the file instead of the convention is the whole point.
 *
 * The files are world-readable text under the system partitions, so the app reads them directly; no
 * shell involved.
 */
internal class KeyLayoutFiles(
    private val readFile: (String) -> String? = { path ->
        runCatching { File(path).takeIf { it.canRead() }?.readText() }.getOrNull()
    },
) {
    data class KeyLayout(
        val keyLabels: Map<Int, String> = emptyMap(),
        val axisLabels: Map<Int, String> = emptyMap(),
    )

    /** The layout Android applies to this device: its vendor:product file, else Generic.kl. */
    fun resolve(vendorId: Int, productId: Int): KeyLayout {
        val specific = String.format("Vendor_%04x_Product_%04x.kl", vendorId, productId)
        for (name in listOf(specific, "Generic.kl")) {
            for (dir in SEARCH_DIRS) {
                val text = readFile("$dir/$name") ?: continue
                return parse(text)
            }
        }
        return KeyLayout()
    }

    internal fun parse(text: String): KeyLayout {
        val keys = mutableMapOf<Int, String>()
        val axes = mutableMapOf<Int, String>()

        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size < 3) {
                return@forEach
            }
            // `key usage 0x…` lines map HID usages, not evdev codes — a number parse refuses them.
            val code = tokens[1].toEvdevCode() ?: return@forEach
            val label = tokens[2]
            // Axis lines can carry modifiers (`split`, `invert`, `flat`) where the label would be.
            // Those axes are doing something more complicated than "this axis is X", so no label is
            // recorded and the caller falls back to the plain code conventions.
            if (!label.matches(Regex("[A-Z][A-Z0-9_]*"))) {
                return@forEach
            }
            when (tokens[0]) {
                "key" -> keys[code] = label
                "axis" -> axes[code] = label
            }
        }
        return KeyLayout(keyLabels = keys, axisLabels = axes)
    }

    private fun String.toEvdevCode(): Int? =
        if (startsWith("0x") || startsWith("0X")) drop(2).toIntOrNull(16) else toIntOrNull()

    private companion object {
        // The order Android itself searches: device-owned partitions before the generic system one.
        val SEARCH_DIRS = listOf(
            "/odm/usr/keylayout",
            "/vendor/usr/keylayout",
            "/system/usr/keylayout",
        )
    }
}
