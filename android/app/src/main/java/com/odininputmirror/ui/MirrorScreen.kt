package com.odininputmirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.ui.theme.Palette

@Composable
fun MirrorScreen(viewModel: MirrorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.screen),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Header(enabled = state.enabled, docked = state.docked, loading = state.loading)

            Spacer(Modifier.height(24.dp))
            Text("Automatic Dock Mirror", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Controllers are selected automatically while dock mode is active.",
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                DeviceCard(
                    modifier = Modifier.weight(1f),
                    title = "Local Controller",
                    device = state.localDevice,
                    hint = when {
                        state.autoMirrorEnabled -> "Locked while mirror is on"
                        state.hasKnownInternalProfile -> "Auto-detected · tap to change"
                        else -> "Tap to select internal"
                    },
                    enabled = !state.autoMirrorEnabled,
                    onClick = if (state.busy || state.autoMirrorEnabled) null else ({ pickerOpen = true }),
                )
                DeviceCard(
                    modifier = Modifier.weight(1f),
                    title = "External Controller",
                    device = state.externalDevice,
                    hint = "First connected controller",
                    enabled = true,
                    onClick = null,
                )
            }

            Spacer(Modifier.height(20.dp))
            SettingRow(
                title = "Home as Back",
                description = "External controller Home button acts as Back",
                checked = state.homeAsBack,
                enabled = !(state.enabled || state.busy),
                onCheckedChange = { viewModel.toggleHomeAsBack(it) },
            )
            Spacer(Modifier.height(12.dp))
            SettingRow(
                title = "Select + Start closes app",
                description = "Hold Select and Start for 3 seconds to close the current app",
                checked = state.comboHoldKillApp,
                enabled = !(state.enabled || state.busy),
                onCheckedChange = { viewModel.toggleComboHoldKillApp(it) },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                if (state.loading) "Scanning controllers..."
                else if (state.restartWaiting) "Waiting for automatic mirror start..."
                else state.message,
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )
            if (!state.loading && !state.enabled && state.autoMirrorEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The mirror starts automatically when the Odin controller and an external controller are available.",
                    color = Palette.textMuted,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = when {
                    state.busy -> "Processing..."
                    state.autoMirrorEnabled -> "Turn Off Automatic Mirror"
                    else -> "Turn On Automatic Mirror"
                },
                stop = state.autoMirrorEnabled,
                enabled = !state.busy,
                onClick = { viewModel.toggleAutoMirrorEnabled(!state.autoMirrorEnabled) },
            )
        }
    }

    if (pickerOpen) {
        InternalControllerPicker(
            devices = state.devices,
            selectedGuid = state.manualInternalGuid,
            onSelect = { guid ->
                pickerOpen = false
                viewModel.selectInternalController(guid)
            },
            onClose = { pickerOpen = false },
        )
    }
}

@Composable
private fun Header(enabled: Boolean, docked: Boolean, loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Docking Enhancer", color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (enabled) "ACTIVE" else "INACTIVE", enabled)
            StatusPill(if (docked) "DOCKED" else "UNDOCKED", docked)
        }
    }
}

@Composable
private fun StatusPill(text: String, on: Boolean) {
    Text(
        text = text,
        color = if (on) Palette.accent else Palette.danger,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Palette.accentSoft else Palette.dangerSoft)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun DeviceCard(
    modifier: Modifier,
    title: String,
    device: ControllerDevice?,
    hint: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val selected = device != null
    Column(
        modifier = modifier
            .heightIn(min = 164.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.surfaceRaised else Palette.surface)
            .border(
                width = 1.dp,
                color = if (selected) Palette.borderStrong else Palette.border,
                shape = RoundedCornerShape(14.dp),
            )
            .let { if (onClick != null) it.clickable(enabled = enabled) { onClick() } else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🎮", fontSize = 36.sp)
        Spacer(Modifier.height(14.dp))
        Text(title, color = Color(0xFFC8DCFA), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = Palette.textMuted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = device?.name ?: "No device",
            color = if (selected) Palette.textPrimary else Palette.textMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.surface)
            .border(1.dp, Palette.border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = Palette.textSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFD7F1FF),
                checkedTrackColor = Color(0xFF1585C3),
                uncheckedThumbColor = Color(0xFF7E8494),
                uncheckedTrackColor = Color(0xFF2B2D38),
            ),
        )
    }
}

@Composable
private fun PrimaryButton(text: String, stop: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (stop) Color(0xFF56232B) else Color(0xFF11486A))
            .border(1.dp, if (stop) Color(0xFF8F3F4A) else Palette.borderStrong, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun InternalControllerPicker(
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
            Text("Select internal controller", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                PickerOption(name = "Automatic (detect)", selected = selectedGuid == null) { onSelect(null) }
                if (devices.isEmpty()) {
                    Text(
                        "No controllers detected. Connect the built-in controller and try again.",
                        color = Palette.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    devices.forEach { device ->
                        PickerOption(
                            name = device.name,
                            selected = device.guid.isNotEmpty() && device.guid == selectedGuid,
                        ) { if (device.guid.isNotEmpty()) onSelect(device.guid) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp),
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
