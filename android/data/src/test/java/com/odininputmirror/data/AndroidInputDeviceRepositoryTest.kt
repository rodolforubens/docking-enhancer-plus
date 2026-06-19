package com.odininputmirror.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidInputDeviceRepositoryTest {
    private val repository = AndroidInputDeviceRepository(pathExists = { true })

    // -- parseProcInputBlock -------------------------------------------------

    @Test
    fun parseProcInputBlockExtractsIdentityNameAndEventPath() {
        val block = """
            I: Bus=0003 Vendor=2020 Product=0112 Version=0000
            N: Name="Xbox Wireless Controller"
            P: Phys=
            S: Sysfs=/devices/virtual/input/input55
            U: Uniq=
            H: Handlers=event7 cpufreq
        """.trimIndent()

        val entry = repository.parseProcInputBlock(block)

        assertEquals("Xbox Wireless Controller", entry?.name)
        assertEquals("/dev/input/event7", entry?.path)
        assertEquals(0x0003, entry?.bus)
        assertEquals(0x2020, entry?.vendorId)
        assertEquals(0x0112, entry?.productId)
    }

    @Test
    fun parseProcInputBlockReturnsNullWhenIdentityLineMissing() {
        val block = """
            N: Name="Orphan device"
            H: Handlers=event3
        """.trimIndent()

        assertNull(repository.parseProcInputBlock(block))
    }

    @Test
    fun parseProcInputBlockReturnsNullWhenNoEventHandler() {
        val block = """
            I: Bus=0003 Vendor=2020 Product=0112 Version=0000
            N: Name="No event handler"
            H: Handlers=cpufreq kgsl
        """.trimIndent()

        assertNull(repository.parseProcInputBlock(block))
    }

    // -- computeMirroredNames -------------------------------------------------

    @Test
    fun computeMirroredNamesIncludesOnlyNonOdinVendorEntries() {
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0111),
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0111),
        )

        val mirroredNames = repository.computeMirroredNames(entries)

        assertEquals(setOf("8bitdo ultimate 2c wireless"), mirroredNames)
    }

    // -- resolveControllerDevice: real bugs fixed today ----------------------

    @Test
    fun internalControllerInXboxModeIsClassifiedAsOdinInternal() {
        // Regression test: the internal Odin controller reports itself with vendor/product ids
        // that match Odin's own id set, but its proc name is "Xbox Wireless Controller", not
        // "Odin Controller". A name.contains("odin") check previously broke this mode entirely.
        val candidate = ControllerCandidate(
            name = "Xbox Wireless Controller",
            vendorId = ODIN_VENDOR_ID,
            productId = 0x0112,
            controllerNumber = 1,
            guid = "guid-xbox-mode",
        )
        val entries = listOf(
            procEntry(name = "Xbox Wireless Controller", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0112),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertTrue(result?.isOdinInternal == true)
    }

    @Test
    fun internalControllerInOdinModeIsClassifiedAsOdinInternal() {
        val candidate = ControllerCandidate(
            name = "Odin Controller",
            vendorId = ODIN_VENDOR_ID,
            productId = 0x0111,
            controllerNumber = 1,
            guid = "guid-odin-mode",
        )
        val entries = listOf(
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0111),
            // The mouse HID Odin also exposes shares the same vendor/product id but a different name.
            procEntry(name = "ODIN Station Virtual Mouse", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0111),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertTrue(result?.isOdinInternal == true)
        assertEquals("Odin Controller", result?.name)
    }

    @Test
    fun externalControllerMirroredUnderOdinVendorIdIsNotClassifiedAsInternal() {
        // Regression test: Odin OS mirrors any connected external controller (e.g. a Bluetooth
        // pad) as a virtual node carrying Odin's own vendor/product id, for compatibility. Only
        // the mirrored proc entry survives the candidate filter here (simulating the real device
        // where the raw Bluetooth report node isn't exposed under /dev/input). Without the
        // mirroredNames guard this would be misclassified as the internal controller.
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = ODIN_VENDOR_ID,
            productId = 0x0111,
            controllerNumber = 3,
            guid = "guid-8bitdo",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0111),
        )
        val mirroredNames = setOf("8bitdo ultimate 2c wireless")

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertFalse(result?.isOdinInternal == true)
    }

    @Test
    fun externalControllerWithDistinctVendorIsNotClassifiedAsInternal() {
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = 0x2dc8,
            productId = 0x301b,
            controllerNumber = 3,
            guid = "guid-8bitdo-bt",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0111),
        )
        val mirroredNames = repository.computeMirroredNames(entries)

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertFalse(result?.isOdinInternal == true)
        assertEquals(BUS_BLUETOOTH, entries.first { it.path == result?.path }.bus)
    }

    @Test
    fun resolveControllerDeviceReturnsNullWhenNoCandidateMatches() {
        val candidate = ControllerCandidate(
            name = "Unknown Pad",
            vendorId = 0x1234,
            productId = 0x5678,
            controllerNumber = 2,
            guid = "guid-unknown",
        )
        val entries = listOf(
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR_ID, productId = 0x0111),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertNull(result)
    }

    private fun procEntry(
        name: String,
        bus: Int,
        vendorId: Int,
        productId: Int,
        eventName: String = "event${entrySequence++}",
    ) = AndroidInputDeviceRepository.ProcInputEntry(
        name = name,
        handlers = listOf(eventName),
        path = "/dev/input/$eventName",
        eventName = eventName,
        bus = bus,
        vendorId = vendorId,
        productId = productId,
    )

    private var entrySequence = 0
}
