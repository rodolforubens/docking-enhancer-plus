package com.odininputmirror.domain.model

/**
 * What one end of a binding refers to.
 *
 * The wire values are the daemon's, because this travels to it as numbers. Naming a kind is what lets
 * the two ends disagree: a controller that reports its triggers as axes can still fill a button slot,
 * and a button can stand in for a stick direction — neither of which was expressible while buttons and
 * axes lived in separate tables that could not cross.
 */
enum class ControlKind(val wire: Int) {
    /** A button. Direction is meaningless and always stored as 0. */
    BUTTON(0),

    /** A whole axis. Direction is its orientation: 1 as-is, -1 flipped. */
    AXIS(1),

    /** One half of an axis's travel, measured from rest. Direction says which half. */
    HALF_AXIS(2),
}

/**
 * One end of a binding: which control, and — on an axis — which way.
 *
 * @param direction 1 or -1 on an axis; 0 on a button.
 */
data class ControlRef(val kind: ControlKind, val code: Int, val direction: Int = 0) {
    companion object {
        fun button(code: Int) = ControlRef(ControlKind.BUTTON, code)
        fun axis(code: Int, direction: Int = 1) =
            ControlRef(ControlKind.AXIS, code, if (direction < 0) -1 else 1)

        fun half(code: Int, direction: Int) =
            ControlRef(ControlKind.HALF_AXIS, code, if (direction < 0) -1 else 1)
    }
}

/** One remapped control: what the pad reports, and what the target should be told instead. */
data class Binding(val source: ControlRef, val target: ControlRef)

/**
 * A user-captured correction for a controller whose layout doesn't match what the mirror assumes.
 *
 * Codes are raw evdev codes on both sides, because that is what the daemon forwards. A control the
 * user skipped simply isn't here, and the daemon forwards it untouched — which is what makes "skip"
 * mean "leave this one alone" rather than "break it".
 */
data class ControllerMapping(val bindings: List<Binding> = emptyList()) {
    val isEmpty: Boolean get() = bindings.isEmpty()

    /** What the card reports: how many controls the user actually pinned down. */
    val boundControlCount: Int get() = bindings.size

    /** True when some binding already reads this exact control, so a second one would fight it. */
    fun claims(source: ControlRef): Boolean = bindings.any { it.source == source }
}

/**
 * Which controller a mapping belongs to.
 *
 * NOT the mirror source's own vendor:product: this firmware republishes every external controller as
 * a `2020:0111` twin and deletes the original node, so that id is identical for every pad and
 * collides with a built-in virtual device. The real controller keeps its own entry in
 * `/proc/bus/input/devices`, and that is what identifies it.
 *
 * Two controllers of the same model share a key, and therefore a mapping — which is what their owner
 * would want anyway.
 */
@JvmInline
value class MappingKey(val value: String) {
    companion object {
        fun of(vendorId: Int, productId: Int): MappingKey =
            MappingKey(String.format("%04x:%04x", vendorId, productId))
    }
}
