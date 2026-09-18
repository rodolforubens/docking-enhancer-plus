package com.odininputmirror.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDisplayDockStateRepositoryTest {
    @Test
    fun thorBottomScreenIsNotAnExternalDock() {
        assertFalse(isExternalDisplayIdentity(name = "Screen-2"))
    }

    @Test
    fun thorDisplayPortOutputIsAnExternalDock() {
        assertTrue(isExternalDisplayIdentity(name = "DP Screen"))
    }

    @Test
    fun standardHdmiOutputIsAnExternalDock() {
        assertTrue(isExternalDisplayIdentity(name = "HDMI Screen"))
    }

    @Test
    fun displayPortNameIsAnExternalDock() {
        assertTrue(isExternalDisplayIdentity(name = "DisplayPort monitor"))
    }
}
