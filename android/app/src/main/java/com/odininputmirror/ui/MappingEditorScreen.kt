package com.odininputmirror.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odininputmirror.domain.model.MappingSlot
import com.odininputmirror.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * The mapping editor: every slot, what it currently reads, and nothing you are forced to do.
 *
 * A screen rather than a dialog because it is the only thing the user is doing, and the controller is
 * busy answering it rather than driving the app behind. Back leaves.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MappingEditorScreen(
    state: MappingEditorUiState,
    onBind: (MappingSlot) -> Unit,
    onClear: (MappingSlot) -> Unit,
    onCancelBind: () -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    // Back means "close the overlay" while one is up, and "leave" otherwise — anything else would
    // make the overlay a trap, since the pad that would normally dismiss it is the one being captured.
    BackHandler { if (state.capturing != null) onCancelBind() else onClose() }

    // One anchor per row, and a memory of which row opened the overlay.
    //
    // Both halves are load-bearing. The handheld opens windows in Touch input mode, where
    // requestFocus() is a silent no-op, so the mode has to be left first and the request retried
    // across a few frames — the focus node is not necessarily placed on the first pass. And when the
    // overlay closes, the node that held focus is destroyed with it: Compose then has nowhere to put
    // focus and the D-pad restarts from the top of the list, which after binding row twenty is a long
    // way from where the user was. Sending it back to the row they came from is the whole point.
    val rowFocus = remember(state.slots.size) { List(state.slots.size) { FocusRequester() } }
    var focusedRow by remember { mutableStateOf(0) }
    val inputModeManager = LocalInputModeManager.current
    val listScroll = rememberScrollState()
    var scrollWhenOpened by remember { mutableStateOf(0) }

    // Which row wants the focus back, if any. Asked for explicitly rather than inferred from the
    // state, because only some changes should move the focus: leaving the overlay and clearing a row
    // both destroy the node that held it, but Clear All is pressed from the footer and has to leave
    // the focus right there — throwing the user to the top of a list they were done with is the
    // opposite of helpful.
    var restoreTo by remember { mutableStateOf<Int?>(null) }

    // Closing the overlay hands the row back. Also fires on the very first composition, with the
    // default of row zero, which is the initial anchor the D-pad needs.
    LaunchedEffect(state.capturing) {
        if (state.capturing == null) restoreTo = focusedRow
    }

    LaunchedEffect(restoreTo, state.slots.size) {
        val target = restoreTo ?: return@LaunchedEffect
        repeat(12) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            val row = rowFocus.getOrNull(target)
            if (row != null && runCatching { row.requestFocus() }.isSuccess) {
                // Put the list back exactly where it was. Taking focus drags the row into view, and
                // "into view" means the nearest edge — so a row that sat comfortably mid-screen comes
                // back pinned to the top or the bottom, and the list appears to jump on its own.
                listScroll.scrollTo(scrollWhenOpened)
                restoreTo = null
                return@LaunchedEffect
            }
            delay(40)
        }
        restoreTo = null
    }

    Box(modifier = Modifier.fillMaxSize().background(Palette.screen)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            Text(
                "Controller mapping",
                color = Palette.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.seeded) {
                    // A known pad arrives already mapped from its profile; the editor's job then is
                    // adjustment, and saying so stops "why is everything filled in?" cold.
                    "${state.controllerName} — mapped from its profile. Change anything that feels wrong."
                } else {
                    "${state.controllerName} — anything without a binding is forwarded as the pad reports it."
                },
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(listScroll),
            ) {
                var lastGroup: String? = null
                state.slots.forEachIndexed { index, slot ->
                    if (slot.group != lastGroup) {
                        lastGroup = slot.group
                        Spacer(Modifier.height(if (index == 0) 0.dp else 14.dp))
                        Text(
                            slot.group.uppercase(),
                            color = Palette.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    SlotRow(
                        slot = slot,
                        source = state.bindings[slot.target],
                        onBind = {
                            focusedRow = index
                            // Recorded HERE, which is the whole trick: by the time the overlay closes
                            // the list has already been scrolled by the focus move, so the position
                            // worth returning to is the one from the instant before it opened.
                            scrollWhenOpened = listScroll.value
                            onBind(slot)
                        },
                        onClear = {
                            focusedRow = index
                            scrollWhenOpened = listScroll.value
                            onClear(slot)
                            restoreTo = index
                        },
                        modifier = Modifier.focusRequester(rowFocus[index]),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.boundCount == 0) {
                        "Nothing bound yet."
                    } else {
                        "${state.boundCount} bound — applied when you press Done."
                    },
                    color = Palette.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                EditorButton("Clear all", Palette.dangerSoft, onClearAll)
                EditorButton("Done", Palette.accentSoft, onClose)
            }
        }

        if (state.capturing != null) {
            CaptureOverlay(
                slot = state.capturing,
                source = state.bindings[state.capturing.target],
                notice = state.notice,
                onCancel = onCancelBind,
            )
        }
    }
}
