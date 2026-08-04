package com.odininputmirror.ui

import com.odininputmirror.data.InputMirrorGraph
import com.odininputmirror.domain.model.Binding
import com.odininputmirror.domain.model.CaptureKind
import com.odininputmirror.domain.model.CaptureResult
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.MappingSlot
import com.odininputmirror.domain.model.mappingSlots
import com.odininputmirror.domain.model.odinFallbackTraits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MappingEditorUiState(
    val open: Boolean = false,
    val controllerName: String = "",
    val slots: List<MappingSlot> = emptyList(),
    /** What each slot currently reads, keyed by the slot's target. Absent means "forwarded as is". */
    val bindings: Map<ControlRef, ControlRef> = emptyMap(),
    /** The slot waiting for a press, when the capture overlay is up. */
    val capturing: MappingSlot? = null,
    /** Why the last press was refused, shown in the overlay. */
    val notice: String? = null,
    /** True when what is on screen is an untouched database default rather than the user's work. */
    val seeded: Boolean = false,
) {
    val boundCount: Int get() = bindings.size
}

/**
 * Drives the mapping editor.
 *
 * Every event comes from the daemon: while the mirror runs the controller is grabbed and its node
 * unlinked, so the app never sees its input directly. Binding a slot opens a capture on the daemon
 * and polls the append-only log for a result.
 *
 * A list rather than a walk, because a walk got the default backwards. Most slots on most pads are
 * already right, and marching the user through all twenty-four of them to fix two made the common
 * case the expensive one. Here an untouched slot costs nothing and means exactly what it looks like.
 */
class MappingEditorController(
    private val graph: InputMirrorGraph,
    private val scope: CoroutineScope,
    /** Called once the mapping has been written, so whatever displays it can catch up. */
    private val onMappingChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(MappingEditorUiState())
    val state: StateFlow<MappingEditorUiState> = _state.asStateFlow()

    private var poller: Job? = null
    private var captureOffset = 0L
    private var mappingKey: MappingKey? = null

    /** What was saved when the editor opened, to tell a real edit from a look around. */
    private var openedWith: Map<ControlRef, ControlRef> = emptyMap()

    /** Whether opening threw away bindings aimed at slots that no longer exist. */
    private var purgedOnOpen = false

    /** Whether the mapping on screen came from the database untouched. */
    private var seededOnOpen = false

    fun open(controllerName: String, key: MappingKey) {
        if (_state.value.open) return
        mappingKey = key
        captureOffset = 0L
        scope.launch {
            // Opened on what is already saved, not on a blank slate: the list has to show the user
            // what their pad does today before it can be somewhere they change it.
            // Slots come from what the target actually declares, read from the live device; the
            // measured Odin defaults only stand in when there is nothing to inspect. This is what
            // keeps the editor from ever offering a target the kernel would silently drop.
            val (saved, slots) = withContext(Dispatchers.IO) {
                // The supervisor normally seeds before the mirror ever starts; repeating it here
                // covers an editor opened before any start. A no-op for a pad already mapped.
                graph.seedDefaultMapping(key)
                val mapping = graph.mappingRepository.get(key)
                seededOnOpen = runCatching { graph.mappingRepository.isSeeded(key) }.getOrDefault(false)
                val traits = runCatching { graph.inputDeviceRepository.targetTraits() }.getOrNull()
                mapping to mappingSlots(traits ?: odinFallbackTraits())
            }

            // Drop anything aimed at a target no slot claims any more. A stored binding outlives the
            // slot list, so a corrected target leaves the old one behind: invisible in the list, still
            // in the file, and still pushed to a daemon that can do nothing with it.
            val targets = slots.map { it.target }.toSet()
            openedWith = saved.bindings
                .filter { it.target in targets }
                .associate { it.target to it.source }
            // Worth writing out even if the user changes nothing else: a stale binding still MATCHES
            // its source, so it swallows that event on the way past and hands it to a target the
            // kernel discards. Leaving it in the file would keep costing the user the control it
            // names long after the slot it aimed at stopped existing.
            purgedOnOpen = openedWith.size != saved.bindings.size

            _state.value = MappingEditorUiState(
                open = true,
                controllerName = controllerName,
                slots = slots,
                bindings = openedWith,
                seeded = seededOnOpen,
            )
        }
    }

    /** Start listening for the control that should drive this slot. */
    fun bind(slot: MappingSlot) {
        if (!_state.value.open) return
        _state.update { it.copy(capturing = slot, notice = null) }
        openCapture()
        startPolling()
    }

    /** Close the overlay without changing anything. */
    fun cancelBind() {
        stopPolling()
        scope.launch(Dispatchers.IO) { graph.processRepository.endCapture() }
        _state.update { it.copy(capturing = null, notice = null) }
    }

    /** Back to forwarding whatever the pad reports for this slot. */
    fun clear(slot: MappingSlot) {
        if (!_state.value.open) return
        _state.update {
            it.copy(bindings = it.bindings - slot.target, capturing = null, notice = null, seeded = false)
        }
        stopPolling()
        scope.launch(Dispatchers.IO) { graph.processRepository.endCapture() }
    }

    /** Throw the whole mapping away. Stays open, so the user can see it emptied and start over. */
    fun clearAll() {
        if (!_state.value.open) return
        stopPolling()
        // No longer "the profile as it came": what is on screen is the user's doing from here on,
        // even though clearing it all is what will hand the profile back on the way out.
        _state.update { it.copy(bindings = emptyMap(), capturing = null, notice = null, seeded = false) }
        scope.launch(Dispatchers.IO) { graph.processRepository.endCapture() }
    }

    /**
     * Leave, writing the mapping on the way out.
     *
     * Held until here rather than pushed on every binding: a walk through the list is one edit as far
     * as the user is concerned, and pushing each row separately meant a config write and a daemon
     * reload per press — twenty of them to remap a pad, each one a chance to be caught half-applied.
     */
    fun close() {
        stopPolling()
        val key = mappingKey
        val bindings = _state.value.bindings
        // A visit that changed nothing writes nothing: bumping the generation would make the daemon
        // re-read a document identical to the one it is already running.
        val edited = bindings != openedWith || purgedOnOpen

        scope.launch(Dispatchers.IO) {
            graph.processRepository.endCapture()
            if (key != null && edited) {
                val mapping = ControllerMapping(bindings.map { Binding(it.value, it.key) })
                if (mapping.isEmpty) {
                    graph.mappingRepository.clear(key)
                    // Clearing everything means "back to how this should be", and for a pad the
                    // database knows, that is its profile. Restored HERE rather than at the next
                    // mirror start: otherwise the card sits badge-less claiming the pad has no
                    // mapping while it is about to be handed one, and the pad stays unmapped until
                    // something unrelated happens to restart the daemon. A pad the database does not
                    // know seeds nothing and stays genuinely cleared.
                    graph.seedDefaultMapping(key)
                } else {
                    graph.mappingRepository.save(key, mapping)
                    // Whatever this was before, it is the user's now.
                    graph.mappingRepository.setSeeded(key, false)
                }
                // Advancing the generation is what makes the supervisor push the new config on its
                // next tick — the same path a toggle takes, so the daemon adopts it without a restart.
                graph.settingsRepository.bumpConfigGeneration()
            }
            // After the write, not before: the badge is read straight back out of storage.
            withContext(Dispatchers.Main) { onMappingChanged() }
        }
        _state.value = MappingEditorUiState()
    }

    private fun openCapture() {
        scope.launch(Dispatchers.IO) {
            graph.processRepository.clearCaptures()
            captureOffset = 0L
            // Always the button mode, even for a stick direction: what a slot wants is one control
            // pushed one way, and that is exactly what this mode reports — a code for a button, a
            // code and a direction for an axis.
            graph.processRepository.beginCapture(CaptureKind.BUTTON)
        }
    }

    // Polling rather than a callback because the daemon speaks through a file. 80ms is well under
    // what reads as instant for a button press, and nothing is being forwarded meanwhile anyway.
    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value.capturing != null) {
                val read = graph.processRepository.readCaptures(captureOffset)
                captureOffset = read.offset
                read.results.firstOrNull()?.let { result ->
                    withContext(Dispatchers.Main) { accept(result) }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    private fun accept(result: CaptureResult) {
        val slot = _state.value.capturing ?: return
        val source = when (result) {
            is CaptureResult.Button -> ControlRef.button(result.code)
            // The pad reports this control as an axis — a trigger, usually, or a stick being pushed
            // at a slot. Half of that axis's travel is the source, so it fills any slot properly.
            is CaptureResult.AxisButton -> ControlRef.half(result.code, result.sign)
            is CaptureResult.Stick -> return
        }

        // Refusing beats stealing. Letting the newer binding win silently is how a user ends up with
        // a control torn off a slot they set earlier with nothing on screen to connect the two — and
        // the daemon would have both bindings fighting over the same event anyway.
        val taken = _state.value.bindings.entries.firstOrNull {
            it.value == source && it.key != slot.target
        }
        if (taken != null) {
            val owner = _state.value.slots.firstOrNull { it.target == taken.key }?.label ?: "another slot"
            _state.update { it.copy(notice = "That control is already bound to $owner") }
            // Reopen: the daemon latches as soon as it has a result, so without this the overlay
            // would sit there refusing to see the next press.
            openCapture()
            return
        }

        stopPolling()
        scope.launch(Dispatchers.IO) { graph.processRepository.endCapture() }
        _state.update {
            it.copy(
                bindings = it.bindings + (slot.target to source),
                capturing = null,
                notice = null,
                seeded = false,
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 80L
    }
}
