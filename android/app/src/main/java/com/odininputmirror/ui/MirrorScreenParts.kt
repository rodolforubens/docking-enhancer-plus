package com.odininputmirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.ui.theme.Palette

// The pieces MirrorScreen lays out: status furniture, the two controller cards, a settings row,
// and the one button that starts or stops everything.

@Composable
internal fun Header(enabled: Boolean, docked: Boolean, loading: Boolean) {
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
            StatusPill(if (enabled) "MIRROR ON" else "MIRROR OFF", enabled)
            StatusPill(if (docked) "EXTERNAL DISPLAY" else "NO EXTERNAL DISPLAY", docked)
        }
    }
}

@Composable
internal fun StatusPill(text: String, on: Boolean) {
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
internal fun DeviceCard(
    modifier: Modifier,
    title: String,
    device: ControllerDevice?,
    hint: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    // True when the user has captured a mapping for THIS controller. Drives the green outline and
    // the badge — the card says so at a glance instead of spelling it out in a line of text.
    hasCustomMapping: Boolean = false,
    // True when the controller carries an untouched database default instead: badge only, no green.
    hasSeededMapping: Boolean = false,
    onEditMapping: (() -> Unit)? = null,
) {
    val selected = device != null
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    // Box only so the badge can sit over the corner; the card itself is still the Column below.
    Box(modifier = modifier) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = 164.dp)
            .clip(shape)
            .background(
                when {
                    focused -> Palette.focusBg
                    selected -> Palette.surfaceRaised
                    else -> Palette.surface
                },
            )
            .border(
                // Focus still wins: it is transient and tells you where you are, while the mapping
                // outline is a standing property of the controller.
                width = if (focused || hasCustomMapping) 2.dp else 1.dp,
                color = when {
                    focused -> Palette.focus
                    hasCustomMapping -> Palette.custom
                    selected -> Palette.borderStrong
                    else -> Palette.border
                },
                shape = shape,
            )
            .let {
                if (onClick != null) {
                    it.clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
                } else {
                    it
                }
            }
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

        if (onEditMapping != null && selected) {
            Spacer(Modifier.height(12.dp))
            // Its own focus source, separate from the card's: this is the only way into the wizard,
            // so the D-pad has to be able to land on it and show that it has.
            val mappingInteraction = remember { MutableInteractionSource() }
            val mappingFocused by mappingInteraction.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            mappingFocused -> Palette.focusBg
                            hasCustomMapping -> Palette.customSoft
                            else -> Palette.surface
                        },
                    )
                    .border(
                        width = if (mappingFocused) 2.dp else 1.dp,
                        color = when {
                            mappingFocused -> Palette.focus
                            hasCustomMapping -> Palette.custom
                            else -> Palette.border
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(interactionSource = mappingInteraction, indication = null) { onEditMapping() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (hasCustomMapping) "Edit mapping" else "Map controls",
                    color = Palette.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

        // Mutually exclusive by construction: seeded means untouched, and the first edit converts it
        // to custom. Green says "yours", the accent blue says "came with the controller".
        when {
            hasCustomMapping ->
                MappingBadge("CUSTOM MAPPING", Palette.custom, Palette.customSoft, Modifier.align(Alignment.TopEnd))
            hasSeededMapping ->
                MappingBadge("DEFAULT", Palette.accent, Palette.accentSoft, Modifier.align(Alignment.TopEnd))
        }
    }
}

// Floats over the card's top-right corner rather than sitting in its flow, so a controller that
// carries a custom mapping is legible before reading a word of the card — and so the badge costs the
// card no height. Inset from the corner rather than flush against it: a pill hard up against a
// rounded border reads as a rendering mistake.
@Composable
internal fun MappingBadge(text: String, ink: Color, fill: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(1.dp, ink, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            color = ink,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun ChoiceSettingRow(
    title: String,
    description: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
            .clip(shape)
            .background(if (focused) Palette.focusBg else Palette.surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Palette.focus else Palette.border,
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = Palette.textSecondary, fontSize = 12.sp)
        }
        Text(
            text = value,
            color = Palette.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp),
        )
    }
}

@Composable
internal fun PrimaryButton(
    text: String,
    stop: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .focusRequester(focusRequester)
            .clip(shape)
            .background(if (stop) Color(0xFF56232B) else Color(0xFF11486A))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Palette.focus
                    stop -> Color(0xFF8F3F4A)
                    else -> Palette.borderStrong
                },
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}
