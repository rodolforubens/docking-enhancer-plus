package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.model.findSavedControllerDevice
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorUseCaseTest {
    @Test
    fun startMirrorStartsProcessThenPersistsFullRequest() {
        val process = FakeMirrorProcessRepository()
        val settings = FakeMirrorSettingsRepository()
        val request = mirrorRequest(
            source = "/dev/input/event9",
            target = "/dev/input/event2",
            sourceGuid = "external-guid",
            targetGuid = "local-guid",
            homeAsBack = true,
            comboHoldKillApp = true,
        )

        StartMirrorUseCase(process, settings, nowMillis = { 1234L })(request)

        assertEquals(listOf(request), process.startRequests)
        assertEquals(
            MirrorSettings(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                sourceGuid = "external-guid",
                targetGuid = "local-guid",
                homeAsBack = true,
                comboHoldKillApp = true,
                expectedRunning = true,
                startedAt = 1234L,
            ),
            settings.state,
        )
    }

    @Test
    fun stopMirrorStopsProcessClearsFilesAndMarksMirrorAsNotExpected() {
        val process = FakeMirrorProcessRepository()
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(source = "/dev/input/event9", target = "/dev/input/event2", expectedRunning = true),
        )

        StopMirrorUseCase(process, settings)()

        assertEquals(1, process.stopCount)
        assertEquals(1, process.clearProcessFilesCount)
        assertFalse(settings.state.expectedRunning)
    }

    @Test
    fun stopMirrorStillClearsFilesAndMarksNotExpectedWhenStopFails() {
        val process = FakeMirrorProcessRepository(throwOnStop = true)
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(source = "/dev/input/event9", target = "/dev/input/event2", expectedRunning = true),
        )

        runCatching { StopMirrorUseCase(process, settings)() }

        assertEquals(1, process.stopCount)
        assertEquals(1, process.clearProcessFilesCount)
        assertFalse(settings.state.expectedRunning)
    }

    @Test
    fun getMirrorStatusUsesFastRunningCheckByDefaultAndReturnsPersistedSettings() {
        val process = FakeMirrorProcessRepository(running = true, verifiedRunning = false)
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                sourceGuid = "external-guid",
                targetGuid = "local-guid",
                homeAsBack = true,
                comboHoldKillApp = false,
                autoRestart = true,
                expectedRunning = true,
            ),
        )

        val status = GetMirrorStatusUseCase(process, settings)()

        assertTrue(status.running)
        assertTrue(status.expectedRunning)
        assertEquals("/dev/input/event9", status.source)
        assertEquals("/dev/input/event2", status.target)
        assertEquals("external-guid", status.sourceGuid)
        assertEquals("local-guid", status.targetGuid)
        assertTrue(status.homeAsBack)
        assertFalse(status.comboHoldKillApp)
        assertTrue(status.autoRestart)
        assertEquals(1, process.isRunningCount)
        assertEquals(0, process.isRunningVerifiedCount)
    }

    @Test
    fun getMirrorStatusVerifiedClearsProcessFilesWhenRootCheckSaysStopped() {
        val process = FakeMirrorProcessRepository(running = true, verifiedRunning = false)
        val settings = FakeMirrorSettingsRepository(MirrorSettings(expectedRunning = true))

        val status = GetMirrorStatusUseCase(process, settings)(verifyWithRoot = true)

        assertFalse(status.running)
        assertEquals(0, process.isRunningCount)
        assertEquals(1, process.isRunningVerifiedCount)
        assertEquals(1, process.clearProcessFilesCount)
    }

    @Test
    fun restartStopsSupervisorWhenAutoRestartIsDisabled() {
        val decision = restartUseCase(
            settings = MirrorSettings(autoRestart = false, expectedRunning = true),
        )()

        assertEquals(RestartDecision.StopSupervisor, decision)
    }

    @Test
    fun restartIdlesWhenMirrorIsNotExpectedToRun() {
        val decision = restartUseCase(
            settings = MirrorSettings(autoRestart = true, expectedRunning = false),
        )()

        assertEquals(RestartDecision.Idle, decision)
    }

    @Test
    fun restartDoesNothingWhenMirrorIsAlreadyRunning() {
        val process = FakeMirrorProcessRepository(running = true)
        val decision = restartUseCase(
            settings = MirrorSettings(autoRestart = true, expectedRunning = true),
            process = process,
        )()

        assertEquals(RestartDecision.Running, decision)
        assertEquals(0, process.clearProcessFilesCount)
        assertTrue(process.startRequests.isEmpty())
    }

    @Test
    fun restartWaitsWhenSavedControllersAreMissing() {
        val process = FakeMirrorProcessRepository(running = false)
        val decision = restartUseCase(
            settings = MirrorSettings(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                sourceGuid = "missing-source",
                targetGuid = "local-guid",
                autoRestart = true,
                expectedRunning = true,
            ),
            process = process,
            devices = listOf(localController(path = "/dev/input/event2", guid = "local-guid")),
        )()

        assertEquals(RestartDecision.WaitingForDevice, decision)
        assertEquals(1, process.clearProcessFilesCount)
        assertTrue(process.startRequests.isEmpty())
    }

    @Test
    fun restartWaitsWhenSavedControllersResolveToSameEventPath() {
        val sharedDevice = localController(path = "/dev/input/event2", guid = "shared-guid")
        val process = FakeMirrorProcessRepository(running = false)
        val decision = restartUseCase(
            settings = MirrorSettings(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                sourceGuid = "shared-guid",
                targetGuid = "shared-guid",
                autoRestart = true,
                expectedRunning = true,
            ),
            process = process,
            devices = listOf(sharedDevice),
        )()

        assertEquals(RestartDecision.WaitingForDevice, decision)
        assertTrue(process.startRequests.isEmpty())
    }

    @Test
    fun restartWaitsWithoutStartingWhenThrottleDisallowsRestart() {
        val process = FakeMirrorProcessRepository(running = false)
        val decision = restartUseCase(
            settings = restartableSettings(),
            process = process,
            devices = reconnectedControllers(),
        )(restartAllowed = false)

        assertEquals(RestartDecision.WaitingForDevice, decision)
        assertEquals(1, process.clearProcessFilesCount)
        assertTrue(process.startRequests.isEmpty())
    }

    @Test
    fun restartUsesGuidBeforeStaleEventPathAndPersistsResolvedPaths() {
        val process = FakeMirrorProcessRepository(running = false)
        val settings = FakeMirrorSettingsRepository(restartableSettings())
        val useCase = restartUseCase(
            settingsRepository = settings,
            process = process,
            devices = reconnectedControllers(),
        )

        val decision = useCase()

        assertEquals(RestartDecision.Restarted, decision)
        assertEquals(
            mirrorRequest(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
                sourceGuid = "external-guid",
                targetGuid = "local-guid",
                homeAsBack = true,
                comboHoldKillApp = true,
            ),
            process.startRequests.single(),
        )
        assertEquals("/dev/input/event11", settings.state.source)
        assertEquals("/dev/input/event4", settings.state.target)
        assertEquals("external-guid", settings.state.sourceGuid)
        assertEquals("local-guid", settings.state.targetGuid)
    }

    @Test
    fun findSavedDevicePrefersGuidBeforePath() {
        val devices = listOf(
            externalController(path = "/dev/input/event9", guid = "old-path-device"),
            externalController(path = "/dev/input/event11", guid = "external-guid"),
        )

        val found = devices.findSavedControllerDevice(
            path = "/dev/input/event9",
            guid = "external-guid",
        )

        assertEquals("/dev/input/event11", found?.path)
    }

    @Test
    fun findSavedDeviceFallsBackToPathWhenGuidIsMissingOrUnknown() {
        val devices = listOf(externalController(path = "/dev/input/event9", guid = "external-guid"))

        val foundWithoutGuid = devices.findSavedControllerDevice(
            path = "/dev/input/event9",
            guid = null,
        )
        val foundWithUnknownGuid = devices.findSavedControllerDevice(
            path = "/dev/input/event9",
            guid = "unknown-guid",
        )

        assertEquals("/dev/input/event9", foundWithoutGuid?.path)
        assertEquals("/dev/input/event9", foundWithUnknownGuid?.path)
    }

    @Test
    fun findSavedDeviceReturnsNullWithoutUsableIdentity() {
        val devices = listOf(externalController())

        assertNull(devices.findSavedControllerDevice(path = null, guid = null))
        assertNull(devices.findSavedControllerDevice(path = "/dev/input/missing", guid = "missing"))
    }

    @Test
    fun setMirrorOptionsPersistIndividualFlagsWithoutChangingOtherSettings() {
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(source = "/dev/input/event9", target = "/dev/input/event2", expectedRunning = true),
        )

        SetHomeAsBackEnabledUseCase(settings)(true)
        SetComboHoldKillAppEnabledUseCase(settings)(true)
        SetAutoRestartEnabledUseCase(settings)(true)

        assertTrue(settings.state.homeAsBack)
        assertTrue(settings.state.comboHoldKillApp)
        assertTrue(settings.state.autoRestart)
        assertTrue(settings.state.expectedRunning)
        assertEquals("/dev/input/event9", settings.state.source)
        assertEquals("/dev/input/event2", settings.state.target)
    }

    @Test
    fun autoMirrorStopsWhenDockIsInactive() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = false,
            devices = reconnectedControllers(),
            settings = MirrorSettings(),
            mirrorRunning = true,
        )

        assertEquals(AutoMirrorDecision.StopForDock, decision)
    }

    @Test
    fun autoMirrorWaitsForInternalController() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(externalController()),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForInternalController, decision)
    }

    @Test
    fun autoMirrorWaitsForExternalController() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(localController()),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForExternalController, decision)
    }

    @Test
    fun autoMirrorIgnoresDuplicateInternalControllerEventAsExternal() {
        val local = localController(controllerNumber = 1)
        val duplicateInternalEvent = externalController(
            path = "/dev/input/event8",
            guid = "odin-mode-secondary-node",
            controllerNumber = 1,
        )

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, duplicateInternalEvent),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForExternalController, decision)
    }

    @Test
    fun autoMirrorStartsWithInternalTargetAndFirstExternalSource() {
        val firstExternal = externalController(path = "/dev/input/event9", guid = "first-external")
        val secondExternal = externalController(path = "/dev/input/event10", guid = "second-external")
        val local = localController(path = "/dev/input/event2", guid = "odin-internal")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, firstExternal, secondExternal),
            settings = MirrorSettings(homeAsBack = true, comboHoldKillApp = true),
            mirrorRunning = false,
        )

        assertEquals(
            AutoMirrorDecision.Start(
                mirrorRequest(
                    source = "/dev/input/event9",
                    target = "/dev/input/event2",
                    sourceGuid = "first-external",
                    targetGuid = "odin-internal",
                    homeAsBack = true,
                    comboHoldKillApp = true,
                )
            ),
            decision,
        )
    }

    @Test
    fun autoMirrorKeepsRunningWhenSelectedDevicesMatch() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = reconnectedControllers(),
            settings = restartableSettings().copy(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
            ),
            mirrorRunning = true,
        )

        assertEquals(AutoMirrorDecision.Running, decision)
    }

    @Test
    fun autoMirrorRestartsWhenExternalControllerChanges() {
        val newExternal = externalController(path = "/dev/input/event12", guid = "new-external")
        val local = localController(path = "/dev/input/event4", guid = "local-guid")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, newExternal),
            settings = restartableSettings().copy(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
            ),
            mirrorRunning = true,
        )

        assertEquals(
            AutoMirrorDecision.Restart(
                mirrorRequest(
                    source = "/dev/input/event12",
                    target = "/dev/input/event4",
                    sourceGuid = "new-external",
                    targetGuid = "local-guid",
                    homeAsBack = true,
                    comboHoldKillApp = true,
                )
            ),
            decision,
        )
    }

    private fun restartUseCase(
        settings: MirrorSettings,
        process: FakeMirrorProcessRepository = FakeMirrorProcessRepository(),
        devices: List<ControllerDevice> = emptyList(),
    ): RestartMirrorIfNeededUseCase {
        return restartUseCase(
            settingsRepository = FakeMirrorSettingsRepository(settings),
            process = process,
            devices = devices,
        )
    }

    private fun restartUseCase(
        settingsRepository: FakeMirrorSettingsRepository,
        process: FakeMirrorProcessRepository = FakeMirrorProcessRepository(),
        devices: List<ControllerDevice> = emptyList(),
    ): RestartMirrorIfNeededUseCase {
        return RestartMirrorIfNeededUseCase(
            inputDeviceRepository = FakeInputDeviceRepository(devices),
            mirrorProcessRepository = process,
            mirrorSettingsRepository = settingsRepository,
        )
    }

    private fun restartableSettings() = MirrorSettings(
        source = "/dev/input/event9",
        target = "/dev/input/event2",
        sourceGuid = "external-guid",
        targetGuid = "local-guid",
        homeAsBack = true,
        comboHoldKillApp = true,
        autoRestart = true,
        expectedRunning = true,
    )

    private fun reconnectedControllers() = listOf(
        externalController(path = "/dev/input/event11", guid = "external-guid"),
        localController(path = "/dev/input/event4", guid = "local-guid"),
    )

    private fun mirrorRequest(
        source: String = "/dev/input/event9",
        target: String = "/dev/input/event2",
        sourceGuid: String? = "external-guid",
        targetGuid: String? = "local-guid",
        homeAsBack: Boolean = false,
        comboHoldKillApp: Boolean = false,
    ) = MirrorStartRequest(
        source = source,
        target = target,
        sourceGuid = sourceGuid,
        targetGuid = targetGuid,
        homeAsBack = homeAsBack,
        comboHoldKillApp = comboHoldKillApp,
    )

    private fun externalController(
        path: String = "/dev/input/event9",
        guid: String = "external-guid",
        controllerNumber: Int = 2,
    ) = ControllerDevice(
        name = "External Controller",
        path = path,
        guid = guid,
        controllerNumber = controllerNumber,
        handlers = listOf("event${path.substringAfterLast("event")}"),
    )

    private fun localController(
        path: String = "/dev/input/event2",
        guid: String = "local-guid",
        controllerNumber: Int = 1,
    ) = ControllerDevice(
        name = "Odin Controller",
        path = path,
        guid = guid,
        controllerNumber = controllerNumber,
        handlers = listOf("event${path.substringAfterLast("event")}"),
        isOdinInternal = true,
    )
}

private class FakeMirrorProcessRepository(
    private val running: Boolean = false,
    private val verifiedRunning: Boolean = false,
    private val throwOnStop: Boolean = false,
) : MirrorProcessRepository {
    val startRequests = mutableListOf<MirrorStartRequest>()
    var stopCount = 0
    var isRunningCount = 0
    var isRunningVerifiedCount = 0
    var clearProcessFilesCount = 0

    override fun start(request: MirrorStartRequest) {
        startRequests += request
    }

    override fun stop() {
        stopCount += 1
        if (throwOnStop) {
            throw IllegalStateException("Stop failed")
        }
    }

    override fun isRunning(): Boolean {
        isRunningCount += 1
        return running
    }

    override fun isRunningVerified(): Boolean {
        isRunningVerifiedCount += 1
        return verifiedRunning
    }

    override fun clearProcessFiles() {
        clearProcessFilesCount += 1
    }
}

private class FakeMirrorSettingsRepository(
    initialState: MirrorSettings = MirrorSettings(),
) : MirrorSettingsRepository {
    var state = initialState

    override fun getSettings(): MirrorSettings = state

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        state = state.copy(
            source = request.source,
            target = request.target,
            sourceGuid = request.sourceGuid,
            targetGuid = request.targetGuid,
            homeAsBack = request.homeAsBack,
            comboHoldKillApp = request.comboHoldKillApp,
            expectedRunning = true,
            startedAt = startedAt,
        )
    }

    override fun saveRestarted(source: String, target: String, sourceGuid: String?, targetGuid: String?) {
        state = state.copy(
            source = source,
            target = target,
            sourceGuid = sourceGuid,
            targetGuid = targetGuid,
        )
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

    override fun setAutoRestartEnabled(enabled: Boolean) {
        state = state.copy(autoRestart = enabled)
    }

    override fun setAutoMirrorEnabled(enabled: Boolean) {
        state = state.copy(autoMirrorEnabled = enabled)
    }
}

private class FakeInputDeviceRepository(
    private val devices: List<ControllerDevice>,
) : InputDeviceRepository {
    override fun getConnectedControllers(): List<ControllerDevice> = devices

    override fun findSavedDevice(
        path: String?,
        guid: String?,
        devices: List<ControllerDevice>,
    ): ControllerDevice? = devices.findSavedControllerDevice(path = path, guid = guid)
}
