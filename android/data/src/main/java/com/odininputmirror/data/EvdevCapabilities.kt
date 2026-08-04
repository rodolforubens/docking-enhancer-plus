package com.odininputmirror.data

/**
 * Decode a capability bitmask as `/proc/bus/input/devices` prints them (`B: KEY=…`, `B: ABS=…`).
 *
 * The line is a sequence of native-long-sized hex words, most significant first, so the RIGHTMOST
 * word covers bits 0..63 and each word to its left the next 64. Word size is the kernel's long —
 * 64-bit on every device this app can run on (arm64), which is why 64 is not a parameter.
 *
 * These bits are the ground truth for what a device can receive: the kernel silently drops an event
 * whose code the device does not declare, so anything offering targets must start here.
 */
internal fun parseCapabilityBits(mask: String): Set<Int> {
    val words = mask.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) {
        return emptySet()
    }

    val bits = mutableSetOf<Int>()
    words.asReversed().forEachIndexed { wordIndex, word ->
        val value = word.toULongOrNull(16) ?: return@forEachIndexed
        for (bit in 0 until 64) {
            if (value shr bit and 1UL == 1UL) {
                bits += wordIndex * 64 + bit
            }
        }
    }
    return bits
}
