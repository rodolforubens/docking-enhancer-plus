package com.odininputmirror.data

import com.odininputmirror.domain.model.Binding
import com.odininputmirror.domain.model.ControlKind
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.ControllerMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingSerializationTest {
    @Test
    fun mappingSurvivesARoundTrip() {
        val mapping = ControllerMapping(
            listOf(
                Binding(ControlRef.button(304), ControlRef.button(305)),
                Binding(ControlRef.axis(0, 1), ControlRef.axis(3, 1)),
                Binding(ControlRef.axis(1, -1), ControlRef.axis(4, 1)),
                // The two that the old button-table / axis-table split could not express at all.
                Binding(ControlRef.half(2, 1), ControlRef.button(312)),
                Binding(ControlRef.button(307), ControlRef.half(0, -1)),
            ),
        )

        assertEquals(mapping, parseMapping(mapping.serialize()))
    }

    @Test
    fun absentOrBlankStorageIsAnEmptyMapping() {
        assertTrue(parseMapping(null).isEmpty)
        assertTrue(parseMapping("").isEmpty)
        assertTrue(parseMapping("   \n  ").isEmpty)
    }

    @Test
    fun oneDamagedRowDoesNotCostTheRowsAroundIt() {
        // Storage is not a document to validate as a whole: a line that went bad should lose that
        // binding, not the user's entire mapping.
        val stored = """
            m 0 304 0 0 305 0
            m 0 not-a-number 0 0 999 0
            m 1 0 1 1 3 1
            m 0 307 0
        """.trimIndent()

        val mapping = parseMapping(stored)

        assertEquals(
            listOf(
                Binding(ControlRef.button(304), ControlRef.button(305)),
                Binding(ControlRef.axis(0, 1), ControlRef.axis(3, 1)),
            ),
            mapping.bindings,
        )
    }

    @Test
    fun anUnknownKindIsDroppedRatherThanGuessedAt() {
        // A code only means something inside its own namespace, so a kind we do not recognise cannot
        // be read as "probably a button" — that would drive a completely unrelated control.
        assertTrue(parseMapping("m 7 304 0 0 305 0\n").isEmpty)
        assertTrue(parseMapping("m 0 304 0 9 305 0\n").isEmpty)
    }

    @Test
    fun onlyANegativeDirectionFlipsAnAxis() {
        // A stored 0 — or anything unexpected — must read as "forward", never silently invert a
        // stick the user never asked to invert.
        val mapping = parseMapping("m 1 0 0 1 3 1\nm 1 1 -7 1 4 1\n")

        assertEquals(1, mapping.bindings[0].source.direction)
        assertEquals(-1, mapping.bindings[1].source.direction)
    }

    @Test
    fun aButtonCarriesNoDirectionWhateverWasStored() {
        val mapping = parseMapping("m 0 304 -1 0 305 1\n")

        assertEquals(ControlKind.BUTTON, mapping.bindings[0].source.kind)
        assertEquals(0, mapping.bindings[0].source.direction)
        assertEquals(0, mapping.bindings[0].target.direction)
    }

    @Test
    fun configRowsMatchTheShapeTheDaemonParses() {
        val mapping = ControllerMapping(
            listOf(
                Binding(ControlRef.button(304), ControlRef.button(307)),
                Binding(ControlRef.half(2, 1), ControlRef.button(312)),
            ),
        )

        assertEquals("[0, 304, 0, 0, 307, 0], [2, 2, 1, 0, 312, 0]", mapping.configBindingRows())
    }

    @Test
    fun anEmptyMappingProducesAnEmptyConfigTable() {
        // Written as an empty table rather than omitted: the daemon rebuilds from what the document
        // names, so leaving it out would keep a mapping the user just cleared.
        assertEquals("", ControllerMapping().configBindingRows())
    }

    @Test
    fun aMappingKnowsWhichSourcesItAlreadyReads() {
        // What stops the wizard letting a later step quietly steal a control from an earlier one.
        val mapping = ControllerMapping(
            listOf(Binding(ControlRef.button(304), ControlRef.button(305))),
        )

        assertTrue(mapping.claims(ControlRef.button(304)))
        assertTrue(!mapping.claims(ControlRef.button(305)))
        assertTrue(!mapping.claims(ControlRef.half(304, 1)))
    }
}
