package com.odininputmirror.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InputMirrorFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val asset = "mirror-binary-content".toByteArray()

    private fun files() = InputMirrorFiles(temp.root) { ByteArrayInputStream(asset) }

    @Test
    fun ensureBinaryInstalledExtractsAssetWhenMissing() {
        val installed = files().ensureBinaryInstalled()

        assertArrayEquals(asset, installed.readBytes())
    }

    @Test
    fun ensureBinaryInstalledReExtractsWhenInstalledCopyDiffersFromAsset() {
        // The binary runs as root: a copy whose content no longer matches the bundled asset
        // (corruption, tampering, app update) must be replaced, never reused.
        val files = files()
        val installed = files.ensureBinaryInstalled()
        installed.writeBytes("tampered".toByteArray())

        val reinstalled = files.ensureBinaryInstalled()

        assertArrayEquals(asset, reinstalled.readBytes())
    }

    @Test
    fun prepareProcessFilesDeletesStalePidAndHeartbeat() {
        val files = files()
        files.pidFile.writeText("123")
        files.heartbeatFile.writeText("456")

        files.prepareProcessFiles()

        assertFalse(files.pidFile.exists())
        assertFalse(files.heartbeatFile.exists())
    }
}
