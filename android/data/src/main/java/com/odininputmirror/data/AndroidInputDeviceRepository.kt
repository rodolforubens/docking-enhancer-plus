package com.odininputmirror.data

import android.view.InputDevice
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.repository.InputDeviceRepository
import java.io.File

internal class AndroidInputDeviceRepository(
    private val shell: MirrorShell = UnavailableShell,
    private val pathExists: (String) -> Boolean = { File(it).exists() },
    private val manualInternalGuidProvider: () -> String? = { null },
) : InputDeviceRepository {
    override fun getConnectedControllers(): List<ControllerDevice> {
        val procEntries = shell.read("cat /proc/bus/input/devices")
            .split(Regex("\\n\\s*\\n"))
            .mapNotNull { parseProcInputBlock(it) }

        val mirroredNames = computeMirroredNames(procEntries)

        val devices = InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> isPhysicalGameController(device) }
            .map { it.toCandidate() }
            .mapNotNull { candidate -> resolveControllerDevice(candidate, procEntries, mirroredNames) }
            .distinctBy { it.path }
            .sortedBy { it.controllerNumber }
            .toList()

        return resolveInternalController(devices, manualInternalGuidProvider())
    }

    // Decides which controller is the internal one:
    //  1. The user's manual pick wins, if its controller is connected — even over a recognised
    //     signature, so the auto-detection can always be overridden just in case.
    //  2. Otherwise a recognised handheld signature (Odin).
    //  3. Otherwise the first detected controller, assumed to be the always-attached built-in.
    internal fun resolveInternalController(
        devices: List<ControllerDevice>,
        manualGuid: String?,
    ): List<ControllerDevice> {
        if (devices.isEmpty()) {
            return devices
        }

        val manualMatch = manualGuid?.let { guid -> devices.firstOrNull { it.guid == guid } }
        if (manualMatch != null) {
            return devices.map { device -> device.copy(isInternal = device.path == manualMatch.path) }
        }

        if (devices.any { it.isKnownInternal }) {
            return devices
        }

        val first = devices.first()
        return devices.map { device ->
            if (device.path == first.path) device.copy(isInternal = true) else device
        }
    }

    // Some handhelds (e.g. the Odin) re-expose any active controller as a virtual HID node
    // carrying the handheld's own vendor id, for game-compatibility reasons. Such a mirrored
    // node always has a "twin" proc entry with the same name on a real, non-quirk vendor id
    // (e.g. the actual Bluetooth identity). The genuine internal controller has no such twin,
    // so this distinguishes it from a mirrored external controller. Only relevant when a
    // mirroring-quirk device is actually present; otherwise there is nothing to disambiguate.
    internal fun computeMirroredNames(procEntries: List<ProcInputEntry>): Set<String> {
        val hasQuirkDevice = procEntries.any { it.vendorId in MIRRORING_QUIRK_VENDOR_IDS }
        if (!hasQuirkDevice) {
            return emptySet()
        }
        return procEntries
            .filter { it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS }
            .map { it.name.lowercase() }
            .toSet()
    }

    internal fun resolveControllerDevice(
        candidate: ControllerCandidate,
        entries: List<ProcInputEntry>,
        mirroredNames: Set<String>,
    ): ControllerDevice? {
        val normalizedName = candidate.name.lowercase()
        val candidates = entries.filter { entry ->
            if (!pathExists(entry.path)) {
                return@filter false
            }

            val sameVendorProduct =
                candidate.vendorId != 0 &&
                    candidate.productId != 0 &&
                    candidate.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                    entry.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                    entry.vendorId == candidate.vendorId &&
                    entry.productId == candidate.productId
            val sameName = entry.name.lowercase() == normalizedName
            sameVendorProduct || sameName
        }

        return candidates
            .sortedWith(
                compareByDescending<ProcInputEntry> {
                    candidate.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                        it.vendorId == candidate.vendorId &&
                        it.productId == candidate.productId
                }
                    .thenByDescending { it.bus == BUS_BLUETOOTH }
                    .thenByDescending { it.bus == BUS_USB && it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS }
                    .thenBy { it.isInternalControllerSignature }
                    .thenBy { it.eventNumber }
            )
            .firstOrNull()
            ?.let { entry ->
                ControllerDevice(
                    name = entry.name,
                    path = entry.path,
                    guid = candidate.guid,
                    controllerNumber = candidate.controllerNumber,
                    handlers = entry.handlers,
                    isInternal = entry.isInternalControllerSignature && entry.name.lowercase() !in mirroredNames,
                    isKnownInternal = entry.isInternalControllerSignature && entry.name.lowercase() !in mirroredNames,
                )
            }
    }

    internal fun parseProcInputBlock(block: String): ProcInputEntry? {
        val identityMatch = Regex("I: Bus=([0-9A-Fa-f]+) Vendor=([0-9A-Fa-f]+) Product=([0-9A-Fa-f]+)")
            .find(block)
            ?: return null
        val name = Regex("N: Name=\"([^\"]+)\"").find(block)?.groupValues?.getOrNull(1) ?: return null
        val handlersLine = Regex("H: Handlers=(.+)").find(block)?.groupValues?.getOrNull(1) ?: return null
        val eventName = Regex("\\bevent\\d+\\b").find(handlersLine)?.value ?: return null

        return ProcInputEntry(
            name = name,
            handlers = handlersLine.split(Regex("\\s+")).filter { it.isNotBlank() },
            path = "/dev/input/$eventName",
            eventName = eventName,
            bus = identityMatch.groupValues[1].toInt(16),
            vendorId = identityMatch.groupValues[2].toInt(16),
            productId = identityMatch.groupValues[3].toInt(16),
        )
    }

    private fun isPhysicalGameController(device: InputDevice?): Boolean {
        device ?: return false
        if (device.isVirtual) return false

        val sources = device.sources
        val hasControllerSource =
            sources.hasSource(InputDevice.SOURCE_GAMEPAD) ||
                sources.hasSource(InputDevice.SOURCE_JOYSTICK)
        if (!hasControllerSource) return false

        val hasControllerButtons = device.hasKeys(*CONTROLLER_BUTTONS).any { it }
        val hasControllerAxes = device.motionRanges.any { range ->
            CONTROLLER_AXES.contains(range.axis)
        }
        return hasControllerButtons || hasControllerAxes
    }

    internal data class ProcInputEntry(
        val name: String,
        val handlers: List<String>,
        val path: String,
        val eventName: String,
        val bus: Int,
        val vendorId: Int,
        val productId: Int,
    ) {
        val eventNumber: Int
            get() = eventName.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE

        val isInternalControllerSignature: Boolean
            get() = matchedInternalSignature(vendorId, productId) != null
    }
}

internal data class ControllerCandidate(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val controllerNumber: Int,
    val guid: String,
)

private fun InputDevice.toCandidate(): ControllerCandidate = ControllerCandidate(
    name = name,
    vendorId = vendorId,
    productId = productId,
    controllerNumber = controllerNumber,
    guid = getGuid(),
)

private fun InputDevice.getGuid(): String = String.format("%016x%016x", productId, vendorId)

private fun Int.hasSource(source: Int): Boolean = this and source == source
