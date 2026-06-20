package com.odininputmirror.data

import com.odininputmirror.domain.model.ControllerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ODIN_VENDOR = 0x2020

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
    fun computeMirroredNamesIncludesOnlyNonQuirkVendorEntries() {
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )

        val mirroredNames = repository.computeMirroredNames(entries)

        assertEquals(setOf("8bitdo ultimate 2c wireless"), mirroredNames)
    }

    @Test
    fun computeMirroredNamesIsEmptyWhenNoMirroringQuirkDevicePresent() {
        // A generic handheld (no Odin-style mirroring quirk): nothing to disambiguate.
        val entries = listOf(
            procEntry(name = "Retro Pad", bus = BUS_USB, vendorId = 0x1234, productId = 0x5678),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b),
        )

        assertTrue(repository.computeMirroredNames(entries).isEmpty())
    }

    // -- resolveControllerDevice: recognised Odin signature ------------------

    @Test
    fun internalControllerInXboxModeIsClassifiedAsInternal() {
        // The internal Odin controller reports a vendor/product that matches a known signature,
        // even though in Xbox mode its proc name is "Xbox Wireless Controller".
        val candidate = ControllerCandidate(
            name = "Xbox Wireless Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0112,
            controllerNumber = 1,
            guid = "guid-xbox-mode",
        )
        val entries = listOf(
            procEntry(name = "Xbox Wireless Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0112),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertTrue(result?.isInternal == true)
    }

    @Test
    fun internalControllerInOdinModeIsClassifiedAsInternal() {
        val candidate = ControllerCandidate(
            name = "Odin Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 1,
            guid = "guid-odin-mode",
        )
        val entries = listOf(
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
            // The mouse HID Odin also exposes shares the same vendor/product id but a different name.
            procEntry(name = "ODIN Station Virtual Mouse", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertTrue(result?.isInternal == true)
        assertEquals("Odin Controller", result?.name)
    }

    @Test
    fun externalControllerMirroredUnderQuirkVendorIdIsNotClassifiedAsInternal() {
        // Odin re-exposes a connected external controller as a virtual node carrying its own
        // vendor/product id. The mirroredNames guard keeps it from being seen as the internal pad.
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 3,
            guid = "guid-8bitdo",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )
        val mirroredNames = setOf("8bitdo ultimate 2c wireless")

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertFalse(result?.isInternal == true)
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
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )
        val mirroredNames = repository.computeMirroredNames(entries)

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertFalse(result?.isInternal == true)
        assertEquals(BUS_BLUETOOTH, entries.first { it.path == result?.path }.bus)
    }

    @Test
    fun unknownHandheldInternalPadIsNotClassifiedAsInternalBySignature() {
        // A non-Odin handheld: its built-in pad uses an unrecognised vendor, so no signature
        // matches and it is NOT auto-flagged internal. Such devices rely on manual selection.
        val candidate = ControllerCandidate(
            name = "Anbernic Gamepad",
            vendorId = 0x1234,
            productId = 0x0001,
            controllerNumber = 1,
            guid = "guid-anbernic",
        )
        val entries = listOf(
            procEntry(name = "Anbernic Gamepad", bus = BUS_USB, vendorId = 0x1234, productId = 0x0001),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertEquals("Anbernic Gamepad", result?.name)
        assertFalse(result?.isInternal == true)
    }

    @Test
    fun resolveControllerDeviceReturnsNullWhenNoCandidateMatches() {
        val candidate = ControllerCandidate(
            name = "Unknown Pad",
            vendorId = 0x9999,
            productId = 0x5678,
            controllerNumber = 2,
            guid = "guid-unknown",
        )
        val entries = listOf(
            procEntry(name = "Odin Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertNull(result)
    }

    // -- resolveInternalController -------------------------------------------

    @Test
    fun internalDefaultsToFirstDetectedControllerOnUnknownDeviceWithNoManualPick() {
        // Non-Odin handheld: the first detected controller is assumed to be the built-in one.
        val devices = listOf(
            device(name = "Anbernic Gamepad", guid = "guid-builtin"),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = null)

        assertTrue(result.first { it.guid == "guid-builtin" }.isInternal)
        assertFalse(result.first { it.guid == "guid-external" }.isInternal)
    }

    @Test
    fun manualPickOverridesTheDefaultFirstControllerOnUnknownDevice() {
        val devices = listOf(
            device(name = "Anbernic Gamepad", guid = "guid-builtin"),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = "guid-external")

        assertTrue(result.first { it.guid == "guid-external" }.isInternal)
        assertFalse(result.first { it.guid == "guid-builtin" }.isInternal)
    }

    @Test
    fun manualPickFallsBackToFirstWhenChosenControllerIsDisconnected() {
        val devices = listOf(device(name = "Anbernic Gamepad", guid = "guid-builtin"))

        val result = repository.resolveInternalController(devices, manualGuid = "guid-gone")

        assertTrue(result.single().isInternal)
    }

    @Test
    fun signatureInternalIsUsedWhenNoManualPick() {
        // On Odin the signature internal is the default when the user has not overridden it.
        val devices = listOf(
            device(name = "Odin Controller", guid = "guid-odin", isInternal = true, isKnownInternal = true),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = null)

        assertEquals(devices, result)
    }

    @Test
    fun manualPickOverridesEvenARecognisedSignatureInternal() {
        // The user can override auto-detection on Odin too, just in case it picked wrong.
        val devices = listOf(
            device(name = "Odin Controller", guid = "guid-odin", isInternal = true, isKnownInternal = true),
            device(name = "8BitDo", guid = "guid-external"),
        )

        val result = repository.resolveInternalController(devices, manualGuid = "guid-external")

        assertTrue(result.first { it.guid == "guid-external" }.isInternal)
        assertFalse(result.first { it.guid == "guid-odin" }.isInternal)
    }

    private fun device(
        name: String,
        guid: String,
        isInternal: Boolean = false,
        isKnownInternal: Boolean = false,
    ) = ControllerDevice(
        name = name,
        path = "/dev/input/$guid",
        guid = guid,
        controllerNumber = 0,
        handlers = emptyList(),
        isInternal = isInternal,
        isKnownInternal = isKnownInternal,
    )

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
