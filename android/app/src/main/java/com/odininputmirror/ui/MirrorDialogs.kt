package com.odininputmirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.odininputmirror.domain.model.AutoMirrorTrigger
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.GestureAction
import com.odininputmirror.ui.theme.Palette

// Modal surfaces for unsupported hardware and manual internal/external controller selection.

@Composable
internal fun UnsupportedDeviceDialog(serviceUnresponsive: Boolean) {
    // No dismiss handler: the mirror cannot run here, so the notice stays put.
    Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A2230))
                .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text("Unsupported device", color = Palette.danger, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (serviceUnresponsive) {
                    // Some firmware publishes the service while the process behind it is dead.
                    // Saying "your device doesn't have it" would be plainly wrong on hardware that
                    // ships it, so this case gets its own wording.
                    "Your device ships the PServer service the mirror needs, but it isn't " +
                        "responding. This can happen on some firmware versions."
                } else {
                    "This app needs the built-in PServer service that ships on handhelds like the AYN " +
                        "Odin 2 family. Your device doesn't have it, so the dock mirror can't run here."
                },
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
internal fun InternalControllerPicker(
    devices: List<ControllerDevice>,
    selectedGuid: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
) {
    ControllerPickerDialog(
        title = "Select internal controller",
        automaticLabel = "Automatic (detect)",
        emptyMessage = "No controllers detected. Connect the built-in controller and try again.",
        devices = devices,
        selectedGuid = selectedGuid,
        onSelect = onSelect,
        onClose = onClose,
    )
}

@Composable
internal fun ExternalControllerPicker(
    devices: List<ControllerDevice>,
    selectedGuid: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
) {
    ControllerPickerDialog(
        title = "Select external controller",
        automaticLabel = "Automatic (first connected)",
        emptyMessage = "No external controllers detected. Connect a controller and try again.",
        devices = devices,
        selectedGuid = selectedGuid,
        onSelect = onSelect,
        onClose = onClose,
    )
}

@Composable
internal fun AutoMirrorTriggerPicker(
    selected: AutoMirrorTrigger,
    onSelect: (AutoMirrorTrigger) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A2230))
                .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                "Automatic start condition",
                color = Palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(14.dp))
            PickerOption(
                name = "External controller",
                selected = selected == AutoMirrorTrigger.CONTROLLER_CONNECTED,
            ) { onSelect(AutoMirrorTrigger.CONTROLLER_CONNECTED) }
            PickerOption(
                name = "Controller + external display",
                selected = selected == AutoMirrorTrigger.CONTROLLER_AND_DISPLAY,
            ) { onSelect(AutoMirrorTrigger.CONTROLLER_AND_DISPLAY) }
        }
    }
}

internal fun GestureAction.label(): String = when (this) {
    GestureAction.NONE -> "None"
    GestureAction.HOME -> "Home"
    GestureAction.BACK -> "Back"
    GestureAction.RECENTS -> "Recents"
    GestureAction.CLOSE_APP -> "Close app"
    GestureAction.TOGGLE_VIRTUAL_MOUSE -> "Virtual mouse toggle"
    GestureAction.SLEEP -> "Sleep"
}

@Composable
internal fun GestureActionPicker(
    title: String,
    selected: GestureAction,
    onSelect: (GestureAction) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A2230))
                .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(title, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                GestureAction.entries.forEach { action ->
                    PickerOption(name = action.label(), selected = action == selected) { onSelect(action) }
                }
            }
        }
    }
}

@Composable
private fun ControllerPickerDialog(
    title: String,
    automaticLabel: String,
    emptyMessage: String,
    devices: List<ControllerDevice>,
    selectedGuid: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A2230))
                .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(title, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                PickerOption(name = automaticLabel, selected = selectedGuid == null) { onSelect(null) }
                if (devices.isEmpty()) {
                    Text(
                        emptyMessage,
                        color = Palette.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    val duplicateNames = devices.groupingBy { it.name.lowercase() }.eachCount()
                    devices.forEach { device ->
                        PickerOption(
                            name = if ((duplicateNames[device.name.lowercase()] ?: 0) > 1) {
                                "${device.name} · Controller ${device.controllerNumber}"
                            } else {
                                device.name
                            },
                            selected = device.guid.isNotEmpty() && device.guid == selectedGuid,
                        ) { if (device.guid.isNotEmpty()) onSelect(device.guid) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PickerOption(name: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Palette.focusBg else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Palette.accent else Color(0xFF5B6175), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Palette.accent))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            color = Palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
