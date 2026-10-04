package app.aaps.receivers

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.RM
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.plugins.configuration.maintenance.MaintenancePlugin
import app.aaps.plugins.constraints.dstHelper.DstHelperPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class KeepAliveWorkerTest : TestBaseWithProfile() {

    private lateinit var worker: KeepAliveWorker

    @Mock private lateinit var loop: Loop
    @Mock private lateinit var maintenancePlugin: MaintenancePlugin
    @Mock private lateinit var dstHelperPlugin: DstHelperPlugin
    @Mock private lateinit var workerParameters: WorkerParameters
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var ads: AutosensDataStore
    @Mock private lateinit var localAlertUtils: LocalAlertUtils
    @Mock private lateinit var workManager: WorkManager
    @Mock private lateinit var listenableFuture: ListenableFuture<List<WorkInfo>>
    @Mock private lateinit var mockedRxBus: RxBus

    @BeforeEach
    fun setUp() {
        // Keep-alive timestamps outlive worker instances, but must not leak between test cases.
        for (fieldName in listOf("lastReadStatus", "lastRun", "lastIobUpload")) {
            KeepAliveWorker::class.java.getDeclaredField(fieldName).apply { isAccessible = true }.setLong(null, 0L)
        }
        // Configure mocks provided by the base class or declared here.
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(workManager.getWorkInfos(any())).thenReturn(listenableFuture)
        whenever(listenableFuture.get()).thenReturn(emptyList())
        whenever(workerParameters.inputData).thenReturn(workDataOf("schedule" to "KA_5"))
        whenever(commandQueue.readStatus(anyOrNull(), anyOrNull())).thenReturn(true)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
    }

    // Helper to create the worker instance directly
    private fun createWorker(): KeepAliveWorker =
        KeepAliveWorker(context, workerParameters).also {
            // Manually inject all mocks.
            it.persistenceLayer = persistenceLayer
            it.config = config
            it.iobCobCalculator = iobCobCalculator
            it.loop = loop
            it.dateUtil = dateUtil
            it.activePlugin = activePlugin
            it.profileFunction = profileFunction
            it.rxBus = mockedRxBus
            it.commandQueue = commandQueue
            it.maintenancePlugin = maintenancePlugin
            it.preferences = preferences
            it.dstHelperPlugin = dstHelperPlugin
            it.aapsLogger = aapsLogger
            it.localAlertUtils = localAlertUtils
            it.workManager = workManager
            it.rh = rh
        }

    @Test
    fun `checkPump requests status when connection is outdated`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.OPEN_LOOP)
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)).thenReturn(true)
        testPumpPlugin.lastData = now - T.mins(20).msecs()

        // Act
        worker.checkPump()

        // Assert
        verify(commandQueue).readStatus(anyOrNull(), anyOrNull())
        Unit
    }

    @Test
    fun `checkPump sends profile switch event if profile is mismatched`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.OPEN_LOOP)
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        testPumpPlugin.isProfileSet = false

        // Act
        worker.checkPump()

        // Assert
        verify(mockedRxBus).send(any<EventProfileSwitchChanged>())
    }

    @Test
    fun `checkPump does nothing if mode is DISCONNECTED_PUMP`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.DISCONNECTED_PUMP)
        testPumpPlugin.lastData = now - T.mins(20).msecs()

        // Act
        worker.doWorkAndLog()

        // Assert
        verify(commandQueue, never()).readStatus(any(), anyOrNull())
        verify(mockedRxBus, never()).send(any<EventProfileSwitchChanged>())
    }

    @Test
    fun `checkAPS schedules device status upload if BG is missing`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(ads.actualBg()).thenReturn(null)

        // Act
        worker.doWorkAndLog()

        // Assert
        verify(loop).scheduleBuildAndStoreDeviceStatus("KeepAliveWorker")
    }

    @Test
    fun `databaseCleanup does NOT run if it was run less than a day ago`() = runBlocking {
        // Arrange
        worker = createWorker()
        whenever(preferences.get(LongNonKey.LastCleanupRun)).thenReturn(now - T.hours(12).msecs())

        // Act
        worker.doWorkAndLog()

        // Assert
        verify(persistenceLayer, never()).cleanupDatabase(any(), any())
        Unit
    }

    private fun enableConnectionReminders(): MutableMap<LongNonKey, Long> {
        val state = mutableMapOf<LongNonKey, Long>()
        for (key in listOf(LongNonKey.PumpConnectionReminderLastConnection, LongNonKey.PumpConnectionReminderLastMinutes, LongNonKey.PumpConnectionReminderRecoveryAttempted)) {
            whenever(preferences.get(key)).thenAnswer { state[key] ?: 0L }
            doAnswer { invocation -> state[key] = invocation.getArgument(1); Unit }.whenever(preferences).put(eq(key), any())
        }
        whenever(preferences.get(BooleanKey.AlertPumpConnectionReminders)).thenReturn(true)
        whenever(config.APS).thenReturn(true)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        doReturn("Pump connection recovery after 40 minutes").whenever(rh).gs(app.aaps.core.ui.R.string.pump_connection_recovery)
        for (minutes in listOf(20, 40, 60)) {
            doReturn("No pump data for at least $minutes minutes").whenever(rh).gs(app.aaps.core.ui.R.string.pump_connection_reminder, minutes)
        }
        return state
    }

    @Test
    fun `quiet connection reminders persist deduplication across worker instances`() {
        val state = enableConnectionReminders()
        val connection = now - T.mins(20).msecs()
        createWorker().checkPumpConnectionReminder(connection, now, now - T.mins(5).msecs())
        createWorker().checkPumpConnectionReminder(connection, now + T.mins(5).msecs(), now)

        val notifications = argumentCaptor<EventNewNotification>()
        verify(mockedRxBus).send(notifications.capture())
        assertEquals(Notification.PUMP_CONNECTION_REMINDER, notifications.firstValue.notification.id)
        assertEquals(Notification.INFO, notifications.firstValue.notification.level)
        assertNull(notifications.firstValue.notification.soundId)
        assertEquals(20L, state[LongNonKey.PumpConnectionReminderLastMinutes])
    }

    @Test
    fun `fresh pump data dismisses only the quiet reminder and clears its persisted stage`() {
        val state = enableConnectionReminders()
        state[LongNonKey.PumpConnectionReminderLastConnection] = now - T.mins(40).msecs()
        state[LongNonKey.PumpConnectionReminderLastMinutes] = 40L
        createWorker().checkPumpConnectionReminder(now - T.mins(1).msecs(), now, 0L)

        val dismissals = argumentCaptor<EventDismissNotification>()
        verify(mockedRxBus).send(dismissals.capture())
        assertEquals(Notification.PUMP_CONNECTION_REMINDER, dismissals.firstValue.id)
        assertEquals(0L, state[LongNonKey.PumpConnectionReminderLastMinutes])
        verify(mockedRxBus, never()).send(any<EventNewNotification>())
    }

    @Test
    fun `disabling reminders dismisses them but preserves deduplication without generating notifications`() {
        val state = enableConnectionReminders()
        state[LongNonKey.PumpConnectionReminderLastConnection] = now - T.mins(40).msecs()
        state[LongNonKey.PumpConnectionReminderLastMinutes] = 40L
        whenever(preferences.get(BooleanKey.AlertPumpConnectionReminders)).thenReturn(false)
        createWorker().checkPumpConnectionReminder(now - T.mins(60).msecs(), now, now)

        val dismissals = argumentCaptor<EventDismissNotification>()
        verify(mockedRxBus).send(dismissals.capture())
        assertEquals(Notification.PUMP_CONNECTION_REMINDER, dismissals.firstValue.id)
        assertEquals(40L, state[LongNonKey.PumpConnectionReminderLastMinutes])
        assertEquals(now - T.mins(40).msecs(), state[LongNonKey.PumpConnectionReminderLastConnection])
        verify(mockedRxBus, never()).send(any<EventNewNotification>())
    }

    @Test
    fun `legacy pump unreachable alarm remains available when quiet mode is disabled`() {
        enableConnectionReminders()
        whenever(preferences.get(BooleanKey.AlertPumpConnectionReminders)).thenReturn(false)
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)).thenReturn(true)
        testPumpPlugin.lastData = now - T.mins(40).msecs()
        worker = createWorker()
        whenever(dateUtil.now()).thenReturn(now - T.mins(5).msecs())
        worker.checkPump()
        whenever(dateUtil.now()).thenReturn(now)
        worker.checkPump()

        verify(localAlertUtils, atLeastOnce()).checkPumpUnreachableAlarm(testPumpPlugin.lastData, true, false)
        verify(commandQueue, times(2)).readStatus(anyOrNull(), anyOrNull())
    }

    @Test
    fun `quiet connection mode replaces only the generic connection alarm`() {
        enableConnectionReminders()
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)).thenReturn(true)
        testPumpPlugin.lastData = now - T.mins(40).msecs()
        worker = createWorker()
        worker.checkPump()
        worker.checkPump()

        verify(localAlertUtils, never()).checkPumpUnreachableAlarm(any(), any(), any())
        verify(mockedRxBus, atLeastOnce()).send(org.mockito.kotlin.argThat<Event> { this is EventDismissNotification && id == Notification.PUMP_UNREACHABLE })
    }

    @Test
    fun `recovery is persisted only after the command queue accepts the status request`() {
        val state = enableConnectionReminders()
        val connection = now - T.mins(40).msecs()
        whenever(commandQueue.readStatus(anyOrNull(), anyOrNull())).thenReturn(false, true)
        worker = createWorker()

        assertFalse(worker.checkPumpConnectionReminder(connection, now, now - T.mins(5).msecs()))
        assertEquals(0L, state[LongNonKey.PumpConnectionReminderRecoveryAttempted])
        assertTrue(worker.checkPumpConnectionReminder(connection, now, now - T.mins(5).msecs()))
        assertEquals(1L, state[LongNonKey.PumpConnectionReminderRecoveryAttempted])
        assertFalse(createWorker().checkPumpConnectionReminder(connection, now, now - T.mins(5).msecs()))
        verify(commandQueue, times(2)).readStatus(anyOrNull(), anyOrNull())
    }

    @Test
    fun `accepted recovery skips the ordinary status request in the same keepalive pass`() {
        val state = enableConnectionReminders()
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)).thenReturn(true)
        whenever(commandQueue.readStatus(anyOrNull(), anyOrNull())).thenReturn(true)
        testPumpPlugin.lastData = now - T.mins(40).msecs()
        worker = createWorker()
        // An earlier real read request establishes that communication has been attempted.
        whenever(dateUtil.now()).thenReturn(now - T.mins(5).msecs())
        worker.checkPump()
        whenever(dateUtil.now()).thenReturn(now)
        worker.checkPump()

        assertEquals(1L, state[LongNonKey.PumpConnectionReminderRecoveryAttempted])
        verify(commandQueue, times(2)).readStatus(anyOrNull(), anyOrNull())
    }

    @Test
    fun `rejected ordinary status requests cannot supply evidence for a connection failure reminder`() {
        val state = enableConnectionReminders()
        whenever(profileFunction.getRequestedProfile()).thenReturn(profileSwitch)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)).thenReturn(true)
        whenever(commandQueue.readStatus(anyOrNull(), anyOrNull())).thenReturn(false)
        testPumpPlugin.lastData = now - T.mins(20).msecs()
        worker = createWorker()

        whenever(dateUtil.now()).thenReturn(now - T.mins(5).msecs())
        worker.checkPump()
        whenever(dateUtil.now()).thenReturn(now)
        worker.checkPump()

        verify(commandQueue, times(2)).readStatus(anyOrNull(), anyOrNull())
        verify(mockedRxBus, never()).send(any<EventNewNotification>())
        assertEquals(0L, state[LongNonKey.PumpConnectionReminderLastMinutes])
        assertEquals(0L, state[LongNonKey.PumpConnectionReminderRecoveryAttempted])
    }
}
