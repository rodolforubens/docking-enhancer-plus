package com.odininputmirror.data

import android.view.InputDevice
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.findSavedControllerDevice
import com.odininputmirror.domain.repository.InputDeviceRepository
import java.io.File

internal class AndroidInputDeviceRepository(
    private val shell: Shell = Shell(),
) : InputDeviceRepository {
    override fun getConnectedControllers(): List<ControllerDevice> {
        val procEntries = shell.runShell("cat /proc/bus/input/devices")
            .split(Regex("\\n\\s*\\n"))
            .mapNotNull { parseProcInputBlock(it) }

        return InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> isPhysicalGameController(device) }
            .mapNotNull { controller -> findProcEntryForController(controller, procEntries) }
            .distinctBy { it.path }
            .sortedBy { it.controllerNumber }
            .toList()
    }

    override fun findSavedDevice(
        path: String?,
        guid: String?,
        devices: List<ControllerDevice>,
    ): ControllerDevice? = devices.findSavedControllerDevice(path = path, guid = guid)

    private fun parseProcInputBlock(block: String): ProcInputEntry? {
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

    private fun findProcEntryForController(
        controller: InputDevice,
        entries: List<ProcInputEntry>,
    ): ControllerDevice? {
        val normalizedName = controller.name.lowercase()
        val candidates = entries.filter { entry ->
            if (!File(entry.path).exists()) {
                return@filter false
            }

            val sameVendorProduct =
                controller.vendorId != 0 &&
                    controller.productId != 0 &&
                    controller.vendorId != ODIN_VENDOR_ID &&
                    entry.vendorId != ODIN_VENDOR_ID &&
                    entry.vendorId == controller.vendorId &&
                    entry.productId == controller.productId
            val sameName = entry.name.lowercase() == normalizedName
            sameVendorProduct || sameName
        }

        return candidates
            .sortedWith(
                compareByDescending<ProcInputEntry> {
                    controller.vendorId != ODIN_VENDOR_ID &&
                        it.vendorId == controller.vendorId &&
                        it.productId == controller.productId
                }
                    .thenByDescending { it.bus == BUS_BLUETOOTH }
                    .thenByDescending { it.bus == BUS_USB && it.vendorId != ODIN_VENDOR_ID }
                    .thenBy { it.isOdinVirtualController }
                    .thenBy { it.eventNumber }
            )
            .firstOrNull()
            ?.let { entry ->
                ControllerDevice(
                    name = entry.name,
                    path = entry.path,
                    guid = controller.getGuid(),
                    controllerNumber = controller.controllerNumber,
                    handlers = entry.handlers,
                )
            }
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

    private data class ProcInputEntry(
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

        val isOdinVirtualController: Boolean
            get() = bus == BUS_USB && vendorId == ODIN_VENDOR_ID
    }
}

private fun InputDevice.getGuid(): String = String.format("%016x%016x", productId, vendorId)

private fun Int.hasSource(source: Int): Boolean = this and source == source
