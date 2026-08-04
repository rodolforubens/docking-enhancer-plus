package com.odininputmirror.data

import com.odininputmirror.domain.model.Binding
import com.odininputmirror.domain.model.ControlKind
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MappingSlot
import com.odininputmirror.domain.model.SlotIcon

/**
 * SDL_GameControllerDB, as a source of first-time defaults.
 *
 * A database entry says which of the pad's controls plays each standard role (`a:b0`,
 * `dpup:h0.1`, `leftx:a0`). Crossed with the slot list — which says where each role lives on the
 * TARGET — it becomes a ready-made binding table: the pad works the first time it is seen, and the
 * editor edits over that instead of over nothing.
 *
 * The values are SDL joystick indices, not evdev codes. SDL's Linux backend assigns them by walking
 * the device's declared capabilities in code order, so the same walk over the pad's /proc bitmasks
 * recovers the codes; see [SdlJoystickIndex].
 */
internal class GameControllerDb(private val lines: Sequence<String>) {

    /** The database entry for this vendor:product, or null. First match wins, as in SDL. */
    fun entryFor(vendorId: Int, productId: Int): Map<String, String>? {
        for (line in lines) {
            val fields = line.trim().split(',')
            if (fields.size < 3 || line.startsWith("#")) continue
            val guid = fields[0]
            if (guid.length != 32) continue
            if (guidWord(guid, 4) == vendorId && guidWord(guid, 8) == productId) {
                return fields.drop(2)
                    .mapNotNull { field ->
                        val at = field.indexOf(':')
                        if (at <= 0) null else field.take(at) to field.substring(at + 1)
                    }
                    .toMap()
            }
        }
        return null
    }

    // The GUID packs little-endian u16s at fixed byte offsets: bus, crc, VENDOR, 0, PRODUCT, 0,
    // version, 0. Two hex chars per byte, low byte first.
    private fun guidWord(guid: String, byteOffset: Int): Int? {
        val at = byteOffset * 2
        if (at + 4 > guid.length) return null
        val low = guid.substring(at, at + 2).toIntOrNull(16) ?: return null
        val high = guid.substring(at + 2, at + 4).toIntOrNull(16) ?: return null
        return high shl 8 or low
    }

    companion object {
        /**
         * Turn one entry into bindings against this target's slots.
         *
         * `sourceKeys`/`sourceAxes` are the REAL pad's capability bitmasks — the entry's indices
         * were assigned against that device, and the twin the mirror actually reads re-broadcasts
         * its codes unchanged. A role the entry names but the slot list lacks (no such control on
         * the target), or whose index falls outside the pad's capabilities, is skipped: a partial
         * default beats none, and beats a wrong guess.
         */
        fun toMapping(
            entry: Map<String, String>,
            sourceKeys: Set<Int>,
            sourceAxes: Set<Int>,
            slots: List<MappingSlot>,
        ): ControllerMapping {
            val buttons = SdlJoystickIndex.buttonCodes(sourceKeys)
            val axes = SdlJoystickIndex.axisCodes(sourceAxes)
            val byIcon = slots.associateBy { it.icon }
            val bindings = mutableListOf<Binding>()

            fun target(icon: SlotIcon): ControlRef? = byIcon[icon]?.target

            for ((role, spec) in entry) {
                if (role == "platform") continue
                val icon = BUTTON_ROLES[role]
                if (icon != null) {
                    val source = parseSource(spec, buttons, axes, wholeAxis = false) ?: continue
                    val slotTarget = target(icon) ?: continue
                    bindings += Binding(source, slotTarget)
                    continue
                }
                // A stick role names a whole source axis, but it is seeded as its TWO halves onto
                // the two direction slots. Not for the daemon's sake — it forwards a whole-axis
                // binding fine — but because the editor's rows ARE the halves: a whole-axis target
                // matches no slot, so it would display as unbound and be purged as stale on the
                // next open. The seed has to speak the editor's vocabulary to survive it.
                val stick = STICK_AXIS_ROLES[role]
                if (stick != null) {
                    val (positiveIcon, negativeIcon) = stick
                    val source = parseSource(spec, buttons, axes, wholeAxis = true) ?: continue
                    if (source.kind == ControlKind.AXIS) {
                        val forward = source.direction
                        target(positiveIcon)?.let {
                            bindings += Binding(ControlRef.half(source.code, forward), it)
                        }
                        target(negativeIcon)?.let {
                            bindings += Binding(ControlRef.half(source.code, -forward), it)
                        }
                    } else {
                        // A button or single half driving a stick axis is full deflection one way;
                        // the positive-direction slot is the way it drives.
                        target(positiveIcon)?.let { bindings += Binding(source, it) }
                    }
                }
            }
            return ControllerMapping(bindings)
        }

        /**
         * One database value as a source control.
         *
         * `bN` a button, `hH.M` one hat direction, `aN` an axis — whole when the role is an axis
         * (`wholeAxis`), the positive half otherwise, because a button-like role driven by an axis
         * is a trigger and triggers rest at their minimum. `+aN`/`-aN` pick a half explicitly and
         * `~` inverts a whole axis.
         */
        private fun parseSource(
            spec: String,
            buttons: List<Int>,
            axes: List<Int>,
            wholeAxis: Boolean,
        ): ControlRef? {
            var body = spec
            var half = 0
            if (body.startsWith("+")) { half = 1; body = body.drop(1) }
            if (body.startsWith("-")) { half = -1; body = body.drop(1) }
            var inverted = false
            if (body.endsWith("~")) { inverted = true; body = body.dropLast(1) }

            return when {
                body.startsWith("b") -> {
                    val index = body.drop(1).toIntOrNull() ?: return null
                    buttons.getOrNull(index)?.let { ControlRef.button(it) }
                }

                body.startsWith("h") -> {
                    val parts = body.drop(1).split('.')
                    if (parts.size != 2) return null
                    val hat = parts[0].toIntOrNull() ?: return null
                    val mask = parts[1].toIntOrNull() ?: return null
                    val x = ABS_HAT0X + hat * 2
                    // SDL hat masks: 1 up, 2 right, 4 down, 8 left. evdev counts hat Y downward.
                    when (mask) {
                        1 -> ControlRef.half(x + 1, -1)
                        4 -> ControlRef.half(x + 1, 1)
                        8 -> ControlRef.half(x, -1)
                        2 -> ControlRef.half(x, 1)
                        else -> null
                    }
                }

                body.startsWith("a") -> {
                    val index = body.drop(1).toIntOrNull() ?: return null
                    val code = axes.getOrNull(index) ?: return null
                    when {
                        half != 0 -> ControlRef.half(code, half)
                        wholeAxis -> ControlRef.axis(code, if (inverted) -1 else 1)
                        else -> ControlRef.half(code, 1)
                    }
                }

                else -> null
            }
        }

        // The roles that land on a single control of the target.
        private val BUTTON_ROLES = mapOf(
            "a" to SlotIcon.A, "b" to SlotIcon.B, "x" to SlotIcon.X, "y" to SlotIcon.Y,
            "back" to SlotIcon.VIEW, "start" to SlotIcon.MENU,
            "leftshoulder" to SlotIcon.LEFT_BUMPER, "rightshoulder" to SlotIcon.RIGHT_BUMPER,
            "lefttrigger" to SlotIcon.LEFT_TRIGGER, "righttrigger" to SlotIcon.RIGHT_TRIGGER,
            "leftstick" to SlotIcon.LEFT_STICK_CLICK, "rightstick" to SlotIcon.RIGHT_STICK_CLICK,
            "dpup" to SlotIcon.DPAD_UP, "dpdown" to SlotIcon.DPAD_DOWN,
            "dpleft" to SlotIcon.DPAD_LEFT, "dpright" to SlotIcon.DPAD_RIGHT,
        )

        // Stick axes as (positive-direction slot, negative-direction slot): evdev counts X rightward
        // and Y downward, so the positive half of leftx is "right" and of lefty is "down".
        private val STICK_AXIS_ROLES = mapOf(
            "leftx" to (SlotIcon.LEFT_STICK_RIGHT to SlotIcon.LEFT_STICK_LEFT),
            "lefty" to (SlotIcon.LEFT_STICK_DOWN to SlotIcon.LEFT_STICK_UP),
            "rightx" to (SlotIcon.RIGHT_STICK_RIGHT to SlotIcon.RIGHT_STICK_LEFT),
            "righty" to (SlotIcon.RIGHT_STICK_DOWN to SlotIcon.RIGHT_STICK_UP),
        )

        private const val ABS_HAT0X = 0x10
    }
}

/**
 * SDL's Linux joystick numbering, reproduced over capability bitmasks.
 *
 * SDL walks the declared keys from `BTN_JOYSTICK` (0x120) to `KEY_MAX` and then from 0 up to
 * `BTN_JOYSTICK`, handing each declared code the next button index; axes are walked in `ABS_*`
 * order with the whole hat range skipped, because hats become `hN` values instead. Database indices
 * only mean anything through this exact walk.
 */
internal object SdlJoystickIndex {
    private const val BTN_JOYSTICK = 0x120
    private const val KEY_MAX = 0x2ff
    private const val ABS_HAT0X = 0x10
    private const val ABS_HAT3Y = 0x17
    private const val ABS_MAX = 0x3f

    fun buttonCodes(keys: Set<Int>): List<Int> =
        (BTN_JOYSTICK..KEY_MAX).filter { it in keys } + (0 until BTN_JOYSTICK).filter { it in keys }

    fun axisCodes(axes: Set<Int>): List<Int> =
        (0..ABS_MAX).filter { it in axes && it !in ABS_HAT0X..ABS_HAT3Y }
}
