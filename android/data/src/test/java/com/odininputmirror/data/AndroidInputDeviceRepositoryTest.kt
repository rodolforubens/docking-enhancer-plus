package com.odininputmirror.data

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MappingKey
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
    fun parseProcInputBlockExtractsBluetoothAddressAndSysfsInputNumber() {
        val block = """
            I: Bus=0005 Vendor=045e Product=0b13 Version=0101
            N: Name="Xbox Wireless Controller"
            S: Sysfs=/devices/virtual/misc/uhid/0005:045E:0B13.0063/input/input173
            U: Uniq=14:CB:65:B7:E8:ED
            H: Handlers=event11 cpufreq
        """.trimIndent()

        val entry = repository.parseProcInputBlock(block)

        assertEquals("14:CB:65:B7:E8:ED", entry?.uniqueId)
        assertEquals(173, entry?.sysfsInputNumber)
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
    fun externalControllerResolvesItsOdinQuirkTwinPath() {
        // Real BT node + the Odin re-exposed twin. The resolved device is the real node; its twin
        // (quirk vendor 0x2020, same name) is what the hide-external feature unlinks.
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = 0x2dc8,
            productId = 0x301b,
            controllerNumber = 3,
            guid = "guid-8bitdo-bt",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b, eventName = "event9"),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )
        val mirroredNames = repository.computeMirroredNames(entries)

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames)

        assertEquals("/dev/input/event9", result?.path)
        assertEquals("/dev/input/event10", result?.hideNodePath)
    }

    @Test
    fun externalWithoutRealDevNodeHidesTheReExposedNodeItself() {
        // The firmware exposes only the Odin re-exposed node in /dev (the real BT node has no /dev
        // entry). The mirror source IS the quirk node, and that same node is the hide target.
        val repoNoRealDev = AndroidInputDeviceRepository(pathExists = { it != "/dev/input/event9" })
        val candidate = ControllerCandidate(
            name = "8BitDo Ultimate 2C Wireless",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 3,
            guid = "guid-8bitdo",
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_BLUETOOTH, vendorId = 0x2dc8, productId = 0x301b, eventName = "event9"),
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )
        val mirroredNames = repoNoRealDev.computeMirroredNames(entries)

        val result = repoNoRealDev.resolveControllerDevice(candidate, entries, mirroredNames)

        assertEquals("/dev/input/event10", result?.path)
        assertEquals("/dev/input/event10", result?.hideNodePath)
        assertFalse(result?.isInternal == true)
    }

    @Test
    fun thorSameNameControllersResolveToDifferentNodesAndKeepTheirDescriptors() {
        // Thor's AYN mapper may publish both pads as 2020:0111. When the built-in pad is also named
        // like an Xbox controller, vendor/product and name cannot distinguish the two; resolving the
        // list as a group must reserve one event node per Android InputDevice.
        val repoNoRealDev = AndroidInputDeviceRepository(pathExists = { it != "/dev/input/event9" })
        val candidates = listOf(
            ControllerCandidate(
                name = "Xbox Wireless Controller",
                vendorId = ODIN_VENDOR,
                productId = 0x0111,
                controllerNumber = 1,
                guid = "thor-built-in-descriptor",
            ),
            ControllerCandidate(
                name = "Xbox Wireless Controller",
                vendorId = ODIN_VENDOR,
                productId = 0x0111,
                controllerNumber = 2,
                guid = "xbox-external-descriptor",
            ),
        )
        val entries = listOf(
            procEntry("Xbox Wireless Controller", BUS_USB, ODIN_VENDOR, 0x0111, "event2"),
            // The real Xbox identity remains in /proc but its node is removed by the AYN mapper.
            procEntry("Xbox Wireless Controller", BUS_BLUETOOTH, 0x045e, 0x0b13, "event9"),
            procEntry("Xbox Wireless Controller", BUS_USB, ODIN_VENDOR, 0x0111, "event10"),
        )

        val resolved = repoNoRealDev.resolveControllerDevices(
            candidates = candidates,
            entries = entries,
            mirroredNames = repoNoRealDev.computeMirroredNames(entries),
        ).sortedBy { it.controllerNumber }
        val classified = repoNoRealDev.resolveInternalController(resolved, manualGuid = null)

        assertEquals(listOf("/dev/input/event2", "/dev/input/event10"), classified.map { it.path })
        assertEquals(listOf("thor-built-in-descriptor", "xbox-external-descriptor"), classified.map { it.guid })
        assertTrue(classified[0].isInternal)
        assertFalse(classified[1].isInternal)
    }

    @Test
    fun thorVirtualMouseDoesNotStealExternalControllerWithSameVendorProduct() {
        val candidate = ControllerCandidate(
            name = "Xbox Wireless Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 3,
            guid = "xbox-external-descriptor",
        )
        val entries = listOf(
            procEntry("ODIN Station Virtual Mouse", BUS_USB, ODIN_VENDOR, 0x0111, "event10"),
            procEntry("Xbox Wireless Controller", BUS_USB, ODIN_VENDOR, 0x0111, "event12"),
            procEntry("Xbox Wireless Controller", BUS_BLUETOOTH, 0x045e, 0x0b13, "event11"),
        )

        val result = repository.resolveControllerDevice(
            candidate = candidate,
            entries = entries,
            mirroredNames = repository.computeMirroredNames(entries),
        )

        assertEquals("/dev/input/event12", result?.path)
        assertFalse(result?.isInternal == true)
        assertEquals(MappingKey.of(0x045e, 0x0b13), result?.mappingKey)
    }

    @Test
    fun externalBluetoothControllerUsesItsUserAssignedAlias() {
        val candidate = ControllerCandidate(
            name = "Xbox Wireless Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0111,
            controllerNumber = 3,
            guid = "xbox-external-descriptor",
        )
        val entries = listOf(
            procEntry(
                "Xbox Wireless Controller",
                BUS_BLUETOOTH,
                0x045e,
                0x0b13,
                "event11",
                uniqueId = "14:CB:65:B7:E8:ED",
                sysfsInputNumber = 173,
            ),
            procEntry(
                "Xbox Wireless Controller",
                BUS_USB,
                ODIN_VENDOR,
                0x0111,
                "event12",
                sysfsInputNumber = 174,
            ),
        )

        val result = repository.resolveControllerDevice(
            candidate = candidate,
            entries = entries,
            mirroredNames = repository.computeMirroredNames(entries),
            bluetoothAliases = mapOf("14:cb:65:b7:e8:ed" to "Xbox Wireless Controller 2"),
        )

        assertEquals("Xbox Wireless Controller 2", result?.name)
    }

    // -- hiddenSourceDevice (re-materialise a hidden mirror source from /proc) -----------

    @Test
    fun hiddenSourceIsRematerialisedWhenRunningAndItsDevNodeIsGone() {
        val repo = AndroidInputDeviceRepository(
            pathExists = { false }, // the source's /dev node was unlinked (hidden)
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { true },
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )

        val result = repo.hiddenSourceDevice(resolved = emptyList(), procEntries = entries)

        assertEquals(1, result.size)
        assertEquals("/dev/input/event10", result[0].path)
        assertEquals("/dev/input/event10", result[0].hideNodePath)
        assertEquals(guidOf(ODIN_VENDOR, 0x0111), result[0].guid)
        assertFalse(result[0].isInternal)
    }

    @Test
    fun hiddenSourceNotRematerialisedWhenMirrorNotRunning() {
        // A stale persisted source (its /dev node absent) must NOT be revived while nothing runs —
        // otherwise it blocks restarting on the live node after a reconnect.
        val repo = AndroidInputDeviceRepository(
            pathExists = { false },
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { false },
        )
        val entries = listOf(
            procEntry(name = "8BitDo Ultimate 2C Wireless", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )

        assertTrue(repo.hiddenSourceDevice(resolved = emptyList(), procEntries = entries).isEmpty())
    }

    @Test
    fun hiddenSourceNotRematerialisedWhenDevNodeStillPresent() {
        val repo = AndroidInputDeviceRepository(
            pathExists = { true }, // node present → not hidden, framework already has it
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { true },
        )
        val entries = listOf(
            procEntry(name = "Pad", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )

        assertTrue(repo.hiddenSourceDevice(resolved = emptyList(), procEntries = entries).isEmpty())
    }

    @Test
    fun hiddenSourceNotRematerialisedWhenAlreadyResolved() {
        val repo = AndroidInputDeviceRepository(
            pathExists = { false },
            hiddenSourcePathProvider = { "/dev/input/event10" },
            mirrorRunningProvider = { true },
        )
        val entries = listOf(
            procEntry(name = "Pad", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0111, eventName = "event10"),
        )
        val already = listOf(ControllerDevice(name = "Pad", path = "/dev/input/event10", guid = "g", controllerNumber = 1))

        assertTrue(repo.hiddenSourceDevice(resolved = already, procEntries = entries).isEmpty())
    }

    @Test
    fun internalControllerHasNoQuirkTwinPath() {
        // The chosen node is itself the quirk-vendor one → there is no separate twin to hide.
        val candidate = ControllerCandidate(
            name = "Xbox Wireless Controller",
            vendorId = ODIN_VENDOR,
            productId = 0x0112,
            controllerNumber = 1,
            guid = "guid-xbox",
        )
        val entries = listOf(
            procEntry(name = "Xbox Wireless Controller", bus = BUS_USB, vendorId = ODIN_VENDOR, productId = 0x0112, eventName = "event7"),
        )

        val result = repository.resolveControllerDevice(candidate, entries, mirroredNames = emptySet())

        assertNull(result?.hideNodePath)
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

    @Test
    fun mappingKeyComesFromTheRealDeviceNotTheFirmwaresTwin() {
        // The firmware republishes the pad as a 0x2020 twin and deletes the original node. Both
        // entries carry the same name; only the real one carries an identity worth keying on.
        val entries = listOf(
            procEntry("8BitDo Ultimate 2C Wireless", BUS_BLUETOOTH, 0x2dc8, 0x301b),
            procEntry("8BitDo Ultimate 2C Wireless", BUS_USB, 0x2020, 0x0111),
        )

        assertEquals(
            MappingKey("2dc8:301b"),
            repository.resolveMappingKey("8BitDo Ultimate 2C Wireless", entries),
        )
    }

    @Test
    fun twoDifferentPadsGetDifferentMappingKeysDespiteSharingAGuid() {
        // The regression this exists to prevent: both twins are 2020:0111, so keying on the twin
        // would hand the second pad the first pad's mapping.
        val entries = listOf(
            procEntry("8BitDo Ultimate 2C Wireless", BUS_BLUETOOTH, 0x2dc8, 0x301b),
            procEntry("8BitDo Ultimate 2C Wireless", BUS_USB, 0x2020, 0x0111),
            procEntry("Xbox Wireless Controller", BUS_BLUETOOTH, 0x045e, 0x0b13),
            procEntry("Xbox Wireless Controller", BUS_USB, 0x2020, 0x0111),
        )

        val first = repository.resolveMappingKey("8BitDo Ultimate 2C Wireless", entries)
        val second = repository.resolveMappingKey("Xbox Wireless Controller", entries)

        assertEquals(MappingKey("2dc8:301b"), first)
        assertEquals(MappingKey("045e:0b13"), second)
    }

    @Test
    fun mappingKeyIsNullWhenOnlyTheQuirkEntryExists() {
        // The internal controller IS the quirk vendor: there is no real device behind it to key on,
        // and it is never the controller being remapped.
        val entries = listOf(procEntry("Xbox Wireless Controller", BUS_USB, 0x2020, 0x0112))

        assertNull(repository.resolveMappingKey("Xbox Wireless Controller", entries))
    }

    private fun procEntry(
        name: String,
        bus: Int,
        vendorId: Int,
        productId: Int,
        eventName: String = "event${entrySequence++}",
        uniqueId: String? = null,
        sysfsInputNumber: Int? = null,
    ) = AndroidInputDeviceRepository.ProcInputEntry(
        name = name,
        handlers = listOf(eventName),
        path = "/dev/input/$eventName",
        eventName = eventName,
        bus = bus,
        vendorId = vendorId,
        productId = productId,
        uniqueId = uniqueId,
        sysfsInputNumber = sysfsInputNumber,
    )

    private var entrySequence = 0
}
