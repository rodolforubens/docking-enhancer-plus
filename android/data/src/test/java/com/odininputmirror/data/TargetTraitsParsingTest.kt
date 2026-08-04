package com.odininputmirror.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two readers behind the dynamic slot list, fed the EXACT strings measured on the Odin 2 — this
 * feature has been bitten too often by plausible codes the device never declared for the fixtures to
 * be anything but real.
 */
class TargetTraitsParsingTest {
    @Test
    fun absMaskDecodesTheOdinInternalPad() {
        // `B: ABS=30627` as printed for the internal Xbox-mode pad: X, Y, Z, RZ, GAS, BRAKE, HAT0X/Y.
        assertEquals(
            setOf(0x00, 0x01, 0x02, 0x05, 0x09, 0x0a, 0x10, 0x11),
            parseCapabilityBits("30627"),
        )
    }

    @Test
    fun multiWordKeyMaskDecodesRightToLeft() {
        // The internal pad's KEY line. Words are 64-bit, most significant first, so the RIGHTMOST
        // word is bits 0..63 — reading them the other way round lands every button in the wrong
        // century of the code space.
        val bits = parseCapabilityBits("10 f00000000 0 0 0 7fff000000000000 0 40000000 c004000000000 0")

        // Face row and thumbs: 0x130..0x13e.
        for (code in 0x130..0x13e) {
            assertTrue("missing 0x${code.toString(16)}", code in bits)
        }
        // D-pad keys and the APP_SWITCH key.
        assertTrue((0x220..0x223).all { it in bits })
        assertTrue(0x244 in bits)
        // System keys HOME/VOL-/VOL+/BACK.
        assertTrue(setOf(0x66, 0x72, 0x73, 0x9e).all { it in bits })
        // No stray bit below 64: the last word is 0.
        assertTrue(bits.none { it < 64 })
    }

    @Test
    fun keyLayoutParsesHexAndDecimalAndSkipsWhatItCannotUse() {
        val layout = KeyLayoutFiles().parse(
            """
            # comment
            key 304   BUTTON_A
            key 0x133 BUTTON_X
            key usage 0x0c0067 WINDOW
            axis 0x09 RTRIGGER
            axis 10 BRAKE
            axis 0x05 split 0x7f LTRIGGER RTRIGGER
            axis 0x00 invert Y
            """.trimIndent(),
        )

        assertEquals("BUTTON_A", layout.keyLabels[304])
        assertEquals("BUTTON_X", layout.keyLabels[0x133])
        // `key usage` maps HID usages, not evdev codes.
        assertTrue(layout.keyLabels.none { it.value == "WINDOW" })
        assertEquals("RTRIGGER", layout.axisLabels[0x09])
        assertEquals("BRAKE", layout.axisLabels[10])
        // Modified axes (split/invert) are doing something a plain label cannot express; recording
        // one anyway would hand the slot builder a lie.
        assertTrue(0x05 !in layout.axisLabels)
        assertTrue(0x00 !in layout.axisLabels)
    }

    @Test
    fun procBlockCarriesItsCapabilityBits() {
        val repository = AndroidInputDeviceRepository()
        val entry = repository.parseProcInputBlock(
            """
            I: Bus=0005 Vendor=2020 Product=0112 Version=0113
            N: Name="Xbox Wireless Controller"
            H: Handlers=event7 cpufreq
            B: KEY=7fff000000000000 0 0 0 0
            B: ABS=30627
            """.trimIndent(),
        )!!

        assertEquals(setOf(0x00, 0x01, 0x02, 0x05, 0x09, 0x0a, 0x10, 0x11), entry.absBits)
        assertEquals((0x130..0x13e).toSet(), entry.keyBits.map { it }.toSet())
    }
}
