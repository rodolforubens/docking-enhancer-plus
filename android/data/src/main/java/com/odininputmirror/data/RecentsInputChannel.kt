package com.odininputmirror.data

import android.content.Context
import java.io.File

enum class RecentsInputCommand(val wireValue: String) {
    LEFT("left"),
    RIGHT("right"),
    RESUME("resume"),
    CLOSE("close"),
}

/**
 * Small file channel between the privileged input daemon and the accessibility service.
 *
 * The daemon is the only process guaranteed to see controller events when a game consumes them.
 * The service is the only process that can act on Launcher3's task nodes. The state marker tells the
 * daemon when to intercept, and the append-only event file carries those intercepted commands back.
 */
class RecentsInputChannel internal constructor(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val stateFile = File(filesDir, RECENTS_STATE_FILE_NAME)
    private val eventsFile = File(filesDir, RECENTS_EVENTS_FILE_NAME)
    private var offset = 0L

    @Synchronized
    fun activate() {
        if (stateFile.exists()) return
        offset = 0L
        eventsFile.writeText("")
        // Publish active last: once the daemon sees this marker, the event sink already exists.
        stateFile.writeText("active\n")
    }

    @Synchronized
    fun deactivate() {
        // Withdraw active first so the daemon stops writing before the sink is removed.
        stateFile.delete()
        eventsFile.delete()
        offset = 0L
    }

    @Synchronized
    fun readPending(): List<RecentsInputCommand> {
        val length = if (eventsFile.exists()) eventsFile.length() else 0L
        if (length < offset) offset = 0L
        if (length <= offset) return emptyList()

        val bytes = runCatching {
            eventsFile.inputStream().use { stream ->
                stream.channel.position(offset)
                stream.readBytes()
            }
        }.getOrNull() ?: return emptyList()
        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        if (lastNewline < 0) return emptyList()

        offset += lastNewline + 1L
        return bytes.decodeToString(0, lastNewline)
            .lineSequence()
            .mapNotNull { line ->
                RecentsInputCommand.entries.firstOrNull { it.wireValue == line.trim() }
            }
            .toList()
    }
}

internal const val RECENTS_STATE_FILE_NAME = "input_mirror.recents"
internal const val RECENTS_EVENTS_FILE_NAME = "input_mirror.recents.events"
