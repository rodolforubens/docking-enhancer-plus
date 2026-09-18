package com.odininputmirror.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentsInputChannelTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun activationPublishesSinkBeforeStateAndReadsCompleteCommands() {
        val filesDir = temp.newFolder("files")
        val channel = RecentsInputChannel(filesDir)

        channel.activate()

        val state = File(filesDir, RECENTS_STATE_FILE_NAME)
        val events = File(filesDir, RECENTS_EVENTS_FILE_NAME)
        assertTrue(state.exists())
        assertTrue(events.exists())

        events.appendText("left\nright\npartial")
        assertEquals(listOf(RecentsInputCommand.LEFT, RecentsInputCommand.RIGHT), channel.readPending())
        events.appendText("\nresume\nclose\nunknown\n")
        assertEquals(
            listOf(RecentsInputCommand.RESUME, RecentsInputCommand.CLOSE),
            channel.readPending(),
        )

        channel.deactivate()
        assertFalse(state.exists())
        assertFalse(events.exists())
    }
}
