package com.odininputmirror.data

import com.odininputmirror.domain.model.Binding
import com.odininputmirror.domain.model.ControlKind
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.ControllerMapping

/**
 * How a mapping is stored: one binding per line, `m <sk> <sc> <sd> <tk> <tc> <td>`.
 *
 * Line-based rather than JSON so it can be read and written without a parser the unit tests would
 * need a device to exercise — and a stored value that goes bad costs the lines around it nothing.
 *
 * Kinds travel as their wire numbers rather than their names: the same six values go to the daemon,
 * and one spelling for both ends is one fewer place for them to drift apart.
 */
internal fun ControllerMapping.serialize(): String = buildString {
    bindings.forEach { b ->
        append("m ${b.source.kind.wire} ${b.source.code} ${b.source.direction}")
        append(" ${b.target.kind.wire} ${b.target.code} ${b.target.direction}\n")
    }
}

internal fun parseMapping(stored: String?): ControllerMapping {
    if (stored.isNullOrBlank()) {
        return ControllerMapping()
    }

    val bindings = mutableListOf<Binding>()
    stored.lineSequence().forEach { line ->
        val parts = line.trim().split(' ').filter { it.isNotBlank() }
        if (parts.size != 7 || parts[0] != "m") return@forEach
        val numbers = parts.drop(1).map { it.toIntOrNull() }
        if (numbers.any { it == null }) return@forEach

        val source = controlRef(numbers[0]!!, numbers[1]!!, numbers[2]!!) ?: return@forEach
        val target = controlRef(numbers[3]!!, numbers[4]!!, numbers[5]!!) ?: return@forEach
        bindings += Binding(source, target)
    }
    return ControllerMapping(bindings)
}

// An unknown kind is dropped rather than guessed at: a code only means something inside its own
// namespace, so reading a stored 7 as "probably a button" would drive an unrelated control.
private fun controlRef(kind: Int, code: Int, direction: Int): ControlRef? {
    if (code < 0) return null
    return when (kind) {
        ControlKind.BUTTON.wire -> ControlRef.button(code)
        ControlKind.AXIS.wire -> ControlRef.axis(code, direction)
        ControlKind.HALF_AXIS.wire -> ControlRef.half(code, direction)
        else -> null
    }
}

/** The same six values again, as the daemon's config wants them. */
internal fun ControllerMapping.configBindingRows(): String = bindings.joinToString(", ") { b ->
    "[${b.source.kind.wire}, ${b.source.code}, ${b.source.direction}, " +
        "${b.target.kind.wire}, ${b.target.code}, ${b.target.direction}]"
}
