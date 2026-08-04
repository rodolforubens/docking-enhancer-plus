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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.MappingSlot
import com.odininputmirror.domain.model.SlotIcon
import com.odininputmirror.domain.model.describeSource
import com.odininputmirror.ui.theme.Palette
import kotlinx.coroutines.delay

// The editor's repeated pieces: one row per slot, the glyph that names it, the overlay that waits
// for a press, and the button style both of them use.

/**
 * One slot: what it is, and what it currently reads.
 *
 * Focusable on purpose. The external controller is busy answering the capture, but the handheld's own
 * pad is not — it is the mirror target, and while this screen is open nothing is being forwarded to
 * it, so its buttons reach the app normally. Without a visible focus ring there is no way to tell
 * where the D-pad is.
 */
@Composable
internal fun SlotRow(
    slot: MappingSlot,
    source: ControlRef?,
    onBind: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The row itself is NOT focusable: it is a plain container holding two focus targets side by side.
    // Making the whole row clickable is what put Clear inside an already-focusable node, where the
    // D-pad could never reach it — focus stopped at the parent and the child was never a stop of its
    // own.
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) Palette.focusBg else Palette.surface)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Palette.focus else Palette.border,
                    shape = RoundedCornerShape(10.dp),
                )
                .clickable(interactionSource = interaction, indication = null) { onBind() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlotGlyph(slot)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(slot.label, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    source?.let(::describeSource) ?: "No binding",
                    color = if (source == null) Palette.textMuted else Palette.accent,
                    fontSize = 12.sp,
                )
            }
        }
        if (source != null) {
            Spacer(Modifier.width(8.dp))
            EditorButton("Clear", Palette.dangerSoft, onClear)
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * The button as it is marked on the pad.
 *
 * Drawn rather than shipped as artwork: four coloured letters, four short labels and a handful of
 * arrows is less code than an asset pipeline, and it scales with the text size for free.
 */
@Composable
internal fun SlotGlyph(slot: MappingSlot) {
    val glyph = when (slot.icon) {
        SlotIcon.A -> PromptGlyph.A
        SlotIcon.B -> PromptGlyph.B
        SlotIcon.X -> PromptGlyph.X
        SlotIcon.Y -> PromptGlyph.Y
        SlotIcon.LEFT_BUMPER -> PromptGlyph.LEFT_BUMPER
        SlotIcon.RIGHT_BUMPER -> PromptGlyph.RIGHT_BUMPER
        SlotIcon.LEFT_TRIGGER -> PromptGlyph.LEFT_TRIGGER
        SlotIcon.RIGHT_TRIGGER -> PromptGlyph.RIGHT_TRIGGER
        SlotIcon.DPAD_UP -> PromptGlyph.DPAD_UP
        SlotIcon.DPAD_DOWN -> PromptGlyph.DPAD_DOWN
        SlotIcon.DPAD_LEFT -> PromptGlyph.DPAD_LEFT
        SlotIcon.DPAD_RIGHT -> PromptGlyph.DPAD_RIGHT
        SlotIcon.LEFT_STICK_UP -> PromptGlyph.LEFT_STICK_UP
        SlotIcon.LEFT_STICK_DOWN -> PromptGlyph.LEFT_STICK_DOWN
        SlotIcon.LEFT_STICK_LEFT -> PromptGlyph.LEFT_STICK_LEFT
        SlotIcon.LEFT_STICK_RIGHT -> PromptGlyph.LEFT_STICK_RIGHT
        SlotIcon.LEFT_STICK_CLICK -> PromptGlyph.LEFT_STICK_CLICK
        SlotIcon.RIGHT_STICK_UP -> PromptGlyph.RIGHT_STICK_UP
        SlotIcon.RIGHT_STICK_DOWN -> PromptGlyph.RIGHT_STICK_DOWN
        SlotIcon.RIGHT_STICK_LEFT -> PromptGlyph.RIGHT_STICK_LEFT
        SlotIcon.RIGHT_STICK_RIGHT -> PromptGlyph.RIGHT_STICK_RIGHT
        SlotIcon.RIGHT_STICK_CLICK -> PromptGlyph.RIGHT_STICK_CLICK
        SlotIcon.VIEW -> PromptGlyph.VIEW
        SlotIcon.MENU -> PromptGlyph.MENU
    }
    // The face buttons keep the colours they are printed in — on an Xbox pad "the green one" is as
    // much the button's name as the letter is. Everything else is one ink, because it is on the pad.
    val colour = when (slot.icon) {
        SlotIcon.A -> Color(0xFF6CC24A)
        SlotIcon.B -> Color(0xFFE5484D)
        SlotIcon.X -> Color(0xFF3E7BFA)
        SlotIcon.Y -> Color(0xFFF2C037)
        else -> Palette.textSecondary
    }
    Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Text(glyph, color = colour, fontFamily = promptFontFamily(), fontSize = 26.sp)
    }
}

/**
 * The capture prompt. Sits over the list so the slot it belongs to stays visible behind it.
 *
 * Cancel is the only action on it, deliberately. Anything else here is a button the user cannot reach:
 * the first control they press to navigate towards it is the control that gets captured, which binds
 * the slot and closes the prompt. Clearing lives on the row, where nothing is listening.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CaptureOverlay(
    slot: MappingSlot,
    source: ControlRef?,
    notice: String?,
    onCancel: () -> Unit,
) {
    // Focus has to come here, and not just for looks: the handheld's pad is still driving the list
    // behind, so without taking focus a press meant for the overlay would open a different slot.
    val cancelFocus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(slot) {
        repeat(10) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Palette.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.surface)
                .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text(slot.label, color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Press the control on your controller to bind it here.",
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                source?.let { "Currently: ${describeSource(it)}" } ?: "Currently: no binding",
                color = Palette.textMuted,
                fontSize = 13.sp,
            )

            if (notice != null) {
                Spacer(Modifier.height(10.dp))
                Text(notice, color = Palette.danger, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                EditorButton("Cancel", Palette.surface, onCancel, Modifier.focusRequester(cancelFocus))
            }
        }
    }
}

@Composable
internal fun EditorButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            // Still at the accessible minimum for a touch target, just no longer oversized.
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Palette.focusBg else background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Palette.focus else Palette.border,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
