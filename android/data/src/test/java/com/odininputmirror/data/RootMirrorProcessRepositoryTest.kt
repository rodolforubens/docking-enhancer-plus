package com.odininputmirror.data

import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootMirrorProcessRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_000_000L
    private lateinit var files: InputMirrorFiles
    private lateinit var settings: FakeSettingsRepository
    private lateinit var shell: RecordingShell
    private lateinit var procRoot: File
    private lateinit var repository: RootMirrorProcessRepository

    @Before
    fun setUp() {
        files = InputMirrorFiles(temp.newFolder("files")) { ByteArrayInputStream(FAKE_BINARY) }
        settings = FakeSettingsRepository()
        shell = RecordingShell()
        procRoot = temp.newFolder("proc")
        repository = RootMirrorProcessRepository(
            mirrorSettingsRepository = settings,
            files = files,
            shell = shell,
            nowMillis = { now },
            procRoot = procRoot,
        )
    }

    // -- isRunning: heartbeat ---------------------------------------------------

    @Test
    fun isRunningTrueWhenHeartbeatIsFresh() {
        writeHeartbeat(ageMs = 1_000L)

        assertTrue(repository.isRunning())
    }

    @Test
    fun isRunningFalseWhenHeartbeatIsStaleAndNoGraceApplies() {
        writeHeartbeat(ageMs = HEARTBEAT_STALE_MS + 1_000L)

        assertFalse(repository.isRunning())
    }

    @Test
    fun emptyHeartbeatFileIsNotFreshEvenWithRecentMtime() {
        // The daemon writes content on every beat; an empty file means it never actually beat
        // (e.g. a failed launch) and must not count as alive just because its mtime is recent.
        files.heartbeatFile.writeText("")
        files.heartbeatFile.setLastModified(now)

        assertFalse(repository.isRunning())
    }

    // -- isRunning: starting grace ----------------------------------------------

    @Test
    fun graceReportsRunningRightAfterStartBeforeAnyHeartbeat() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - 500L)

        assertTrue(repository.isRunning())
    }

    @Test
    fun graceExpiresAfterItsWindow() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - STARTING_GRACE_MS - 1L)

        assertFalse(repository.isRunning())
    }

    @Test
    fun zombieProcessDuringGraceClearsPidFileAndExpectedRunning() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - 500L)
        files.pidFile.writeText("123\n")
        writeProcStat(pid = 123, state = "Z")

        assertFalse(repository.isRunning())
        assertFalse(files.pidFile.exists())
        assertFalse(settings.state.expectedRunning)
    }

    @Test
    fun liveProcessDuringGraceReportsRunning() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - 500L)
        files.pidFile.writeText("123\n")
        writeProcStat(pid = 123, state = "S")

        assertTrue(repository.isRunning())
    }

    // -- start / stop -------------------------------------------------------------

    @Test
    fun startExtractsBinaryAndLaunchesGuardedQuotedCommand() {
        repository.start(
            MirrorStartRequest(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                homeAsBack = true,
                comboHoldKillApp = true,
                virtualMouse = true,
            )
        )

        val binary = File(File(files.pidFile.parentFile, "bin"), "input_mirror")
        assertTrue(binary.exists())

        val command = shell.launchedDaemons.single()
        assertTrue(command.startsWith("pidof input_mirror >/dev/null 2>&1 && exit 0; "))
        assertTrue(command.contains("'/dev/input/event9' '/dev/input/event2'"))
        assertTrue(command.contains(" --home-as-back"))
        assertTrue(command.contains(" --combo-hold-kill-app"))
        assertTrue(command.contains(" --virtual-mouse"))
        assertTrue(command.contains("--pid-file '${files.pidFile.absolutePath}'"))
        assertTrue(command.contains("--heartbeat-file '${files.heartbeatFile.absolutePath}'"))
        assertTrue(command.contains("--hidden-state-file '${files.hiddenStateFile.absolutePath}'"))
    }

    @Test
    fun healIsNoOpWhenNoHiddenStateFile() {
        repository.healOrphanedHideNodes()

        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun healIsNoOpWhenHiddenStateFileEmpty() {
        files.hiddenStateFile.writeText("")

        repository.healOrphanedHideNodes()

        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun healRunsDaemonHealModeWhenHiddenStateFilePresent() {
        files.hiddenStateFile.writeText("/dev/input/event10 13 74\n")

        repository.healOrphanedHideNodes()

        val command = shell.executed.single()
        assertTrue(command.contains(" --heal "))
        assertTrue(command.contains("--hidden-state-file '${files.hiddenStateFile.absolutePath}'"))
    }

    @Test
    fun startPassesHideNodeArgForEachHiddenNode() {
        repository.start(
            MirrorStartRequest(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                homeAsBack = false,
                comboHoldKillApp = false,
                hideNodes = listOf("/dev/input/event10", "/dev/input/event11"),
            )
        )

        val command = shell.launchedDaemons.single()
        assertTrue(command.contains("--hide-node '/dev/input/event10'"))
        assertTrue(command.contains("--hide-node '/dev/input/event11'"))
    }

    @Test
    fun startOmitsHideNodeArgWhenNoNodesToHide() {
        repository.start(
            MirrorStartRequest(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                homeAsBack = false,
                comboHoldKillApp = false,
            )
        )

        assertFalse(shell.launchedDaemons.single().contains("--hide-node"))
    }

    @Test
    fun startThrowsWhenLaunchCommandIsNotDelivered() {
        shell.deliver = false

        assertThrows(IllegalStateException::class.java) {
            repository.start(
                MirrorStartRequest(
                    source = "/dev/input/event9",
                    target = "/dev/input/event2",
                    homeAsBack = false,
                    comboHoldKillApp = false,
                )
            )
        }
    }

    @Test
    fun stopDeliversStopCommand() {
        repository.stop()

        assertEquals(listOf(STOP_MIRROR_COMMAND), shell.executed)
    }

    @Test
    fun stopThrowsWhenStopCommandIsNotDelivered() {
        shell.deliver = false

        assertThrows(IllegalStateException::class.java) { repository.stop() }
    }

    // -- helpers -------------------------------------------------------------------

    private fun writeHeartbeat(ageMs: Long) {
        files.heartbeatFile.writeText("$now\n")
        files.heartbeatFile.setLastModified(now - ageMs)
    }

    private fun writeProcStat(pid: Int, state: String) {
        val procDir = File(procRoot, pid.toString())
        procDir.mkdirs()
        File(procDir, "stat").writeText("$pid (input_mirror) $state 1 $pid $pid 0 -1\n")
    }

    private companion object {
        val FAKE_BINARY = "fake-binary".toByteArray()
    }
}

private class FakeSettingsRepository(
    var state: MirrorSettings = MirrorSettings(),
) : MirrorSettingsRepository {
    override fun getSettings(): MirrorSettings = state

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        state = state.copy(
            source = request.source,
            target = request.target,
            sourceGuid = request.sourceGuid,
            targetGuid = request.targetGuid,
            expectedRunning = true,
            startedAt = startedAt,
        )
    }

    override fun saveRestarted(source: String, target: String, sourceGuid: String?, targetGuid: String?) {
        state = state.copy(source = source, target = target, sourceGuid = sourceGuid, targetGuid = targetGuid)
    }

    override fun setExpectedRunning(expectedRunning: Boolean) {
        state = state.copy(expectedRunning = expectedRunning)
    }

    override fun setHomeAsBackEnabled(enabled: Boolean) {
        state = state.copy(homeAsBack = enabled)
    }

    override fun setComboHoldKillAppEnabled(enabled: Boolean) {
        state = state.copy(comboHoldKillApp = enabled)
    }

    override fun setVirtualMouseEnabled(enabled: Boolean) {
        state = state.copy(virtualMouse = enabled)
    }

    override fun setAutoMirrorEnabled(enabled: Boolean) {
        state = state.copy(autoMirrorEnabled = enabled)
    }

    override fun setManualInternalController(guid: String?) {
        state = state.copy(manualInternalGuid = guid)
    }
}

private class RecordingShell : MirrorShell {
    var deliver = true
    val executed = mutableListOf<String>()
    val launchedDaemons = mutableListOf<String>()

    override val isAvailable: Boolean = true

    override fun exec(command: String): Boolean {
        executed += command
        return deliver
    }

    override fun read(command: String): String = ""

    override fun launchDaemon(command: String): Boolean {
        launchedDaemons += command
        return deliver
    }
}
