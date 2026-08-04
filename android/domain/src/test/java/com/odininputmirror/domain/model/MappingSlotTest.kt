package com.odininputmirror.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingSlotTest {
    @Test
    fun everySlotHasADistinctTarget() {
        // A duplicate would mean two rows in the list quietly overwriting each other's binding.
        val targets = mappingSlots(odinFallbackTraits()).map { it.target }
        assertEquals(targets.size, targets.toSet().size)
    }

    @Test
    fun odinTraitsProduceTheMeasuredTargets() {
        // Regression pinned to what was measured on the device: dpad on the hat, right stick on
        // Z/RZ, triggers on BRAKE/GAS — the codes the internal pad declares, none of the ones it
        // does not. Every one of these was once wrong precisely because it was assumed.
        val byLabel = mappingSlots(odinFallbackTraits()).associateBy { it.label }

        assertEquals(ControlRef.half(0x11, -1), byLabel.getValue("D-pad up").target)
        assertEquals(ControlRef.half(0x10, 1), byLabel.getValue("D-pad right").target)
        assertEquals(ControlRef.half(0x05, -1), byLabel.getValue("Right stick up").target)
        assertEquals(ControlRef.half(0x02, 1), byLabel.getValue("Right stick right").target)
        assertEquals(ControlRef.half(0x0a, 1), byLabel.getValue("Left trigger").target)
        assertEquals(ControlRef.half(0x09, 1), byLabel.getValue("Right trigger").target)
    }

    @Test
    fun faceLettersFollowTheKeyLayoutFileNotTheHeaderNames() {
        // The firmware's .kl says 0x133 is X and 0x134 is Y — the reverse of what the kernel names
        // (BTN_NORTH/BTN_WEST) suggest for an Xbox pad. Trusting the convention over the file is
        // what once shipped X and Y swapped: the user bound X, the pad pressed Y.
        val byLabel = mappingSlots(odinFallbackTraits()).associateBy { it.label }

        assertEquals(ControlRef.button(0x133), byLabel.getValue("X").target)
        assertEquals(ControlRef.button(0x134), byLabel.getValue("Y").target)
    }

    @Test
    fun xpadStyleTargetGetsRxRyStickAndZRzTriggers() {
        // The other common family: no pedal axes, Z/RZ are the triggers (so says the layout file)
        // and the right stick lives on RX/RY. The trigger axes must not be offered as a stick.
        val xpad = TargetTraits(
            keys = setOf(0x130, 0x131, 0x133, 0x134, 0x220, 0x221, 0x222, 0x223),
            axes = setOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05),
            axisLabels = mapOf(0x02 to "LTRIGGER", 0x05 to "RTRIGGER"),
        )
        val byLabel = mappingSlots(xpad).associateBy { it.label }

        assertEquals(ControlRef.half(0x02, 1), byLabel.getValue("Left trigger").target)
        assertEquals(ControlRef.half(0x03, -1), byLabel.getValue("Right stick left").target)
        assertEquals(ControlRef.half(0x04, -1), byLabel.getValue("Right stick up").target)
    }

    @Test
    fun aTargetWithoutAHatFallsBackToDpadKeys() {
        val traits = TargetTraits(
            keys = setOf(0x130, 0x220, 0x221, 0x222, 0x223),
            axes = setOf(0x00, 0x01),
        )
        val byLabel = mappingSlots(traits).associateBy { it.label }

        assertEquals(ControlRef.button(0x220), byLabel.getValue("D-pad up").target)
    }

    @Test
    fun aControlTheTargetLacksProducesNoSlot() {
        // A slot that can only feed events the kernel drops is worse than its absence.
        val minimal = TargetTraits(keys = setOf(0x130, 0x131), axes = setOf(0x00, 0x01))
        val labels = mappingSlots(minimal).map { it.label }

        assertTrue("Right stick up" !in labels)
        assertTrue("D-pad up" !in labels)
        assertTrue("Left trigger" !in labels)
        assertTrue("Left stick up" in labels)
        assertTrue("A" in labels)
    }

    @Test
    fun eachStickDirectionTakesADistinctHalfOfItsAxis() {
        // The pair that has to be right for a stick to work at all: up and down are the two halves of
        // one axis, and giving them the same half leaves the stick able to move only one way.
        val left = mappingSlots(odinFallbackTraits()).filter { it.group == "Left stick" }

        assertEquals(4, left.size)
        assertTrue(left.all { it.target.kind == ControlKind.HALF_AXIS })
        val up = left.first { it.label.endsWith("up") }.target
        val down = left.first { it.label.endsWith("down") }.target
        assertEquals(up.code, down.code)
        assertEquals(-up.direction, down.direction)
        // evdev counts Y downward, so up is the negative half. Backwards here means an inverted stick.
        assertEquals(-1, up.direction)
    }

    @Test
    fun aBoundControlIsNamedByItsRawCode() {
        // The source is whatever the external pad chose to report; dressing 0x130 up as "A" would be
        // a guess of exactly the kind the user came here to correct.
        assertEquals("Button 304", describeSource(ControlRef.button(304)))
        assertEquals("+Axis 2", describeSource(ControlRef.half(2, 1)))
        assertEquals("−Axis 2", describeSource(ControlRef.half(2, -1)))
        assertEquals("Axis 0 (inverted)", describeSource(ControlRef.axis(0, -1)))
    }
}
