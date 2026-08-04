package com.odininputmirror.domain.model

/**
 * One slot the user can point a control at: what the target should be told, and what to call it.
 *
 * The list of slots is the whole editor. Nothing here is a step in a sequence — a slot with no
 * binding is not "unfinished", it is a control forwarded exactly as the pad reports it, which is the
 * right answer for most slots on most pads. Only the ones that are wrong need touching.
 */
data class MappingSlot(
    val label: String,
    /** Which heading this sits under. Only for grouping the list. */
    val group: String,
    val target: ControlRef,
    /** Which control this is on the pad, for the list to draw. */
    val icon: SlotIcon,
)

/**
 * Which control a slot stands for.
 *
 * Named rather than carrying artwork or a character: what the control *is* belongs here, and how it
 * is drawn belongs to whatever is drawing it.
 */
enum class SlotIcon {
    A, B, X, Y,
    LEFT_BUMPER, RIGHT_BUMPER, LEFT_TRIGGER, RIGHT_TRIGGER,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    LEFT_STICK_UP, LEFT_STICK_DOWN, LEFT_STICK_LEFT, LEFT_STICK_RIGHT, LEFT_STICK_CLICK,
    RIGHT_STICK_UP, RIGHT_STICK_DOWN, RIGHT_STICK_LEFT, RIGHT_STICK_RIGHT, RIGHT_STICK_CLICK,
    VIEW, MENU,
}

/**
 * What the mirror's target declares, read from the device rather than assumed.
 *
 * The lesson this type exists to encode: the kernel silently drops an event whose code the target
 * does not declare, and Android maps the codes it does receive through the device's key layout file
 * — by code number, not by the name the kernel headers give the code. Every wrong guess this feature
 * has shipped came from reasoning about either of those instead of reading them. `keys` and `axes`
 * come from the device's capability bitmasks; the label maps come from its resolved `.kl`.
 */
data class TargetTraits(
    val keys: Set<Int>,
    val axes: Set<Int>,
    /** evdev key code -> Android button name (`BUTTON_A`…), from the key layout file. */
    val keyLabels: Map<Int, String> = emptyMap(),
    /** evdev axis code -> Android axis name (`X`, `RZ`, `LTRIGGER`…), from the key layout file. */
    val axisLabels: Map<Int, String> = emptyMap(),
)

// Standard evdev codes, spelled out rather than pulled from a platform constant so the domain module
// stays free of Android.
private const val BTN_SOUTH = 0x130
private const val BTN_EAST = 0x131
private const val BTN_NORTH = 0x133
private const val BTN_WEST = 0x134
private const val BTN_TL = 0x136
private const val BTN_TR = 0x137
private const val BTN_TL2 = 0x138
private const val BTN_TR2 = 0x139
private const val BTN_SELECT = 0x13a
private const val BTN_START = 0x13b
private const val BTN_THUMBL = 0x13d
private const val BTN_THUMBR = 0x13e
private const val BTN_DPAD_UP = 0x220
private const val BTN_DPAD_DOWN = 0x221
private const val BTN_DPAD_LEFT = 0x222
private const val BTN_DPAD_RIGHT = 0x223

private const val ABS_X = 0x00
private const val ABS_Y = 0x01
private const val ABS_Z = 0x02
private const val ABS_RX = 0x03
private const val ABS_RY = 0x04
private const val ABS_RZ = 0x05
private const val ABS_GAS = 0x09
private const val ABS_BRAKE = 0x0a
private const val ABS_HAT0X = 0x10
private const val ABS_HAT0Y = 0x11

/**
 * The measured traits of the Odin 2's internal pad, for when the live target cannot be inspected.
 *
 * Matches `/proc/bus/input/devices` (`ABS=30627`) and `Vendor_2020_Product_0112.kl` on the device.
 * A fallback and nothing more: the live path reads the same facts from the running system.
 */
fun odinFallbackTraits(): TargetTraits = TargetTraits(
    keys = setOf(
        BTN_SOUTH, BTN_EAST, 0x132, BTN_NORTH, BTN_WEST, 0x135, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
        BTN_SELECT, BTN_START, 0x13c, BTN_THUMBL, BTN_THUMBR,
        BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT,
    ),
    axes = setOf(ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y),
    keyLabels = mapOf(
        BTN_SOUTH to "BUTTON_A", BTN_EAST to "BUTTON_B",
        BTN_NORTH to "BUTTON_X", BTN_WEST to "BUTTON_Y",
        BTN_TL to "BUTTON_L1", BTN_TR to "BUTTON_R1",
        BTN_TL2 to "BUTTON_L2", BTN_TR2 to "BUTTON_R2",
        BTN_SELECT to "BUTTON_SELECT", BTN_START to "BUTTON_START",
        BTN_THUMBL to "BUTTON_THUMBL", BTN_THUMBR to "BUTTON_THUMBR",
    ),
    axisLabels = mapOf(
        ABS_X to "X", ABS_Y to "Y", ABS_Z to "Z", ABS_RZ to "RZ",
        ABS_GAS to "RTRIGGER", ABS_BRAKE to "LTRIGGER",
        ABS_HAT0X to "HAT_X", ABS_HAT0Y to "HAT_Y",
    ),
)

/**
 * Build the slot list from what the target actually declares.
 *
 * Every decision here prefers the device's own answer over a convention:
 *
 * - **Face letters** come from the key layout file. On this firmware `0x133` is X and `0x134` is Y —
 *   the reverse of what the kernel header names (`BTN_NORTH`/`BTN_WEST`) suggest for an Xbox pad,
 *   and following the convention instead of the file is what once shipped X and Y swapped.
 * - **The d-pad** goes to the hat when the target has one, because that is what a pad with a hat
 *   reports natively and what its key layout listens to; only a target without a hat gets the
 *   `BTN_DPAD_*` keys.
 * - **Triggers** go to the axes the layout calls triggers (`LTRIGGER`/`RTRIGGER`/`BRAKE`/`GAS`),
 *   staying analog; a target without trigger axes gets the `BTN_TL2`/`BTN_TR2` keys.
 * - **The right stick** is whichever declared pair is not spoken for by the triggers: `Z`/`RZ` on
 *   HID-style pads (this one), `RX`/`RY` on xpad-style pads where `Z`/`RZ` are the triggers.
 * - A control the target simply does not have produces no slot at all — a slot that can only ever
 *   feed dropped events is worse than its absence.
 */
fun mappingSlots(traits: TargetTraits): List<MappingSlot> {
    val slots = mutableListOf<MappingSlot>()

    fun key(code: Int) = code in traits.keys
    fun axis(code: Int) = code in traits.axes
    // The code the layout file maps to this Android button, falling back to the Generic.kl
    // convention for a target whose layout we could not read.
    fun labelled(label: String, fallback: Int): Int? {
        val code = traits.keyLabels.entries.firstOrNull { it.value == label }?.key ?: fallback
        return code.takeIf(::key)
    }

    // Face buttons, lettered by the layout file.
    labelled("BUTTON_A", BTN_SOUTH)?.let {
        slots += MappingSlot("A", FACE, ControlRef.button(it), SlotIcon.A)
    }
    labelled("BUTTON_B", BTN_EAST)?.let {
        slots += MappingSlot("B", FACE, ControlRef.button(it), SlotIcon.B)
    }
    labelled("BUTTON_X", BTN_NORTH)?.let {
        slots += MappingSlot("X", FACE, ControlRef.button(it), SlotIcon.X)
    }
    labelled("BUTTON_Y", BTN_WEST)?.let {
        slots += MappingSlot("Y", FACE, ControlRef.button(it), SlotIcon.Y)
    }

    labelled("BUTTON_L1", BTN_TL)?.let {
        slots += MappingSlot("Left bumper", SHOULDERS, ControlRef.button(it), SlotIcon.LEFT_BUMPER)
    }
    labelled("BUTTON_R1", BTN_TR)?.let {
        slots += MappingSlot("Right bumper", SHOULDERS, ControlRef.button(it), SlotIcon.RIGHT_BUMPER)
    }

    // Trigger axes: what the layout calls a trigger, wherever it lives.
    val leftTrigger = axisLabelled(traits, setOf("LTRIGGER", "BRAKE"), ABS_BRAKE)
    val rightTrigger = axisLabelled(traits, setOf("RTRIGGER", "GAS"), ABS_GAS)
    when {
        leftTrigger != null ->
            slots += MappingSlot("Left trigger", SHOULDERS, ControlRef.half(leftTrigger, 1), SlotIcon.LEFT_TRIGGER)
        key(BTN_TL2) ->
            slots += MappingSlot("Left trigger", SHOULDERS, ControlRef.button(BTN_TL2), SlotIcon.LEFT_TRIGGER)
    }
    when {
        rightTrigger != null ->
            slots += MappingSlot("Right trigger", SHOULDERS, ControlRef.half(rightTrigger, 1), SlotIcon.RIGHT_TRIGGER)
        key(BTN_TR2) ->
            slots += MappingSlot("Right trigger", SHOULDERS, ControlRef.button(BTN_TR2), SlotIcon.RIGHT_TRIGGER)
    }

    // D-pad: the hat when there is one, the key codes otherwise. evdev counts hat Y downward, so up
    // is the negative half.
    if (axis(ABS_HAT0X) && axis(ABS_HAT0Y)) {
        slots += MappingSlot("D-pad up", DPAD, ControlRef.half(ABS_HAT0Y, -1), SlotIcon.DPAD_UP)
        slots += MappingSlot("D-pad down", DPAD, ControlRef.half(ABS_HAT0Y, 1), SlotIcon.DPAD_DOWN)
        slots += MappingSlot("D-pad left", DPAD, ControlRef.half(ABS_HAT0X, -1), SlotIcon.DPAD_LEFT)
        slots += MappingSlot("D-pad right", DPAD, ControlRef.half(ABS_HAT0X, 1), SlotIcon.DPAD_RIGHT)
    } else if (key(BTN_DPAD_UP)) {
        slots += MappingSlot("D-pad up", DPAD, ControlRef.button(BTN_DPAD_UP), SlotIcon.DPAD_UP)
        slots += MappingSlot("D-pad down", DPAD, ControlRef.button(BTN_DPAD_DOWN), SlotIcon.DPAD_DOWN)
        slots += MappingSlot("D-pad left", DPAD, ControlRef.button(BTN_DPAD_LEFT), SlotIcon.DPAD_LEFT)
        slots += MappingSlot("D-pad right", DPAD, ControlRef.button(BTN_DPAD_RIGHT), SlotIcon.DPAD_RIGHT)
    }

    // Sticks. Up is the axis's NEGATIVE half: evdev counts Y downward.
    if (axis(ABS_X) && axis(ABS_Y)) {
        slots += MappingSlot("Left stick up", LEFT_STICK, ControlRef.half(ABS_Y, -1), SlotIcon.LEFT_STICK_UP)
        slots += MappingSlot("Left stick down", LEFT_STICK, ControlRef.half(ABS_Y, 1), SlotIcon.LEFT_STICK_DOWN)
        slots += MappingSlot("Left stick left", LEFT_STICK, ControlRef.half(ABS_X, -1), SlotIcon.LEFT_STICK_LEFT)
        slots += MappingSlot("Left stick right", LEFT_STICK, ControlRef.half(ABS_X, 1), SlotIcon.LEFT_STICK_RIGHT)
    }
    val taken = setOfNotNull(leftTrigger, rightTrigger)
    val rightPair = listOf(ABS_Z to ABS_RZ, ABS_RX to ABS_RY).firstOrNull { (x, y) ->
        axis(x) && axis(y) && x !in taken && y !in taken
    }
    if (rightPair != null) {
        val (rx, ry) = rightPair
        slots += MappingSlot("Right stick up", RIGHT_STICK, ControlRef.half(ry, -1), SlotIcon.RIGHT_STICK_UP)
        slots += MappingSlot("Right stick down", RIGHT_STICK, ControlRef.half(ry, 1), SlotIcon.RIGHT_STICK_DOWN)
        slots += MappingSlot("Right stick left", RIGHT_STICK, ControlRef.half(rx, -1), SlotIcon.RIGHT_STICK_LEFT)
        slots += MappingSlot("Right stick right", RIGHT_STICK, ControlRef.half(rx, 1), SlotIcon.RIGHT_STICK_RIGHT)
    }

    labelled("BUTTON_SELECT", BTN_SELECT)?.let {
        slots += MappingSlot("View", OTHER, ControlRef.button(it), SlotIcon.VIEW)
    }
    labelled("BUTTON_START", BTN_START)?.let {
        slots += MappingSlot("Menu", OTHER, ControlRef.button(it), SlotIcon.MENU)
    }
    labelled("BUTTON_THUMBL", BTN_THUMBL)?.let {
        slots += MappingSlot("Left stick click", OTHER, ControlRef.button(it), SlotIcon.LEFT_STICK_CLICK)
    }
    labelled("BUTTON_THUMBR", BTN_THUMBR)?.let {
        slots += MappingSlot("Right stick click", OTHER, ControlRef.button(it), SlotIcon.RIGHT_STICK_CLICK)
    }

    return slots
}

private fun axisLabelled(traits: TargetTraits, labels: Set<String>, fallback: Int): Int? {
    val code = traits.axisLabels.entries.firstOrNull { it.value in labels }?.key ?: fallback
    return code.takeIf { it in traits.axes }
}

private const val FACE = "Face buttons"
private const val SHOULDERS = "Shoulders and triggers"
private const val DPAD = "D-pad"
private const val LEFT_STICK = "Left stick"
private const val RIGHT_STICK = "Right stick"
private const val OTHER = "Other"

/**
 * How a bound control reads in the list.
 *
 * Named by its raw code, because that is all we honestly know: the source is whatever the external
 * pad chose to report, and dressing 0x130 up as "A" would be a guess of exactly the kind the user
 * came here to correct.
 */
fun describeSource(source: ControlRef): String = when (source.kind) {
    ControlKind.BUTTON -> "Button ${source.code}"
    ControlKind.AXIS -> if (source.direction < 0) "Axis ${source.code} (inverted)" else "Axis ${source.code}"
    ControlKind.HALF_AXIS -> "${if (source.direction < 0) "−" else "+"}Axis ${source.code}"
}
