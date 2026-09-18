package com.odininputmirror.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityServicesTest {
    @Test
    fun `adds service without replacing an existing one`() {
        assertEquals(
            "com.ayn/.Service:com.odininputmirror/.RecentsAccessibilityService",
            mergedAccessibilityServices(
                "com.ayn/.Service\n",
                "com.odininputmirror/.RecentsAccessibilityService",
            ),
        )
    }

    @Test
    fun `does not duplicate an enabled service`() {
        val component = "com.odininputmirror/.RecentsAccessibilityService"
        assertEquals(component, mergedAccessibilityServices(component, component))
    }

    @Test
    fun `handles an unset Android secure setting`() {
        assertEquals(
            "com.odininputmirror/.RecentsAccessibilityService",
            mergedAccessibilityServices(
                "null\n",
                "com.odininputmirror/.RecentsAccessibilityService",
            ),
        )
    }

    @Test
    fun `does not overwrite services after a failed privileged read`() {
        assertNull(
            mergedAccessibilityServices(
                "",
                "com.odininputmirror/.RecentsAccessibilityService",
            ),
        )
    }
}
