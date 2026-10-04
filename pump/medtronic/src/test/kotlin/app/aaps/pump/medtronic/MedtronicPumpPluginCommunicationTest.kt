package app.aaps.pump.medtronic

import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.common.PumpPluginAbstract
import app.aaps.pump.common.driver.refresh.PumpDataRefreshType
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import app.aaps.pump.common.hw.rileylink.service.tasks.ResetRileyLinkConfigurationTask
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import app.aaps.pump.common.hw.rileylink.service.tasks.WakeAndTuneTask
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.history.pump.PumpHistoryResult
import app.aaps.pump.medtronic.comm.ui.MedtronicUIComm
import app.aaps.pump.medtronic.comm.ui.MedtronicUITask
import app.aaps.pump.medtronic.data.MedtronicHistoryData
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.defs.MedtronicDeviceType
import app.aaps.pump.medtronic.defs.MedtronicUIResponseType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.service.RileyLinkMedtronicService
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

class MedtronicPumpPluginCommunicationTest : MedtronicTestBase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var medtronicHistoryData: MedtronicHistoryData
    @Mock lateinit var rileyLinkServiceData: RileyLinkServiceData
    @Mock lateinit var serviceTaskExecutor: ServiceTaskExecutor
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var service: RileyLinkMedtronicService
    @Mock lateinit var communicationManager: MedtronicCommunicationManager
    @Mock lateinit var uiComm: MedtronicUIComm

    private lateinit var status: MedtronicPumpStatus
    private lateinit var plugin: MedtronicPumpPlugin
    private var lastGoodCommunication = 0L

    @BeforeEach
    fun setup() {
        status = MedtronicPumpStatus(preferences, rxBus, rileyLinkUtil)
        status.medtronicDeviceType = MedtronicDeviceType.Medtronic_722
        lastGoodCommunication = System.currentTimeMillis() - 31 * 60 * 1000L
        status.lastConnection = lastGoodCommunication
        status.previousConnection = lastGoodCommunication - 60_000L

        whenever(medtronicUtil.isModelSet).thenReturn(true)
        whenever(medtronicUtil.medtronicPumpModel).thenReturn(MedtronicDeviceType.Medtronic_722)
        whenever(service.medtronicUIComm).thenReturn(uiComm)
        whenever(service.deviceCommunicationManager).thenReturn(communicationManager)
        // The Bluetooth-error path skips the reachability probe and still attempts status queries.
        // No validated radio response can advance the communication clock in this harness.
        whenever(rileyLinkServiceData.rileyLinkServiceState).thenReturn(RileyLinkServiceState.BluetoothError)

        plugin = MedtronicPumpPlugin(
            aapsLogger = aapsLogger,
            rh = rh,
            preferences = preferences,
            commandQueue = commandQueue,
            rxBus = rxBus,
            context = context,
            activePlugin = activePlugin,
            fabricPrivacy = fabricPrivacy,
            medtronicUtil = medtronicUtil,
            medtronicPumpStatus = status,
            medtronicHistoryData = medtronicHistoryData,
            rileyLinkServiceData = rileyLinkServiceData,
            serviceTaskExecutor = serviceTaskExecutor,
            uiInteraction = uiInteraction,
            dateUtil = dateUtil,
            aapsSchedulers = aapsSchedulers,
            pumpSync = pumpSync,
            pumpSyncStorage = pumpSyncStorage,
            decimalFormatter = decimalFormatter,
            pumpEnactResultProvider = pumpEnactResultProvider,
            wakeAndTuneTaskProvider = Provider { mock<WakeAndTuneTask>() },
            resetRileyLinkConfigurationTaskProvider = Provider { mock<ResetRileyLinkConfigurationTask>() }
        )
        setPluginField("rileyLinkMedtronicService", service)
        setPluginField("isServiceSet", true)
    }

    @Test
    fun `failed scheduled polls retain last successful communication time`() {
        setBaseField("firstRun", false)
        whenever(uiComm.executeCommand(MedtronicCommandType.GetBatteryStatus))
            .thenReturn(errorTask(MedtronicCommandType.GetBatteryStatus))

        repeat(2) {
            scheduleBatteryPollNow()
            plugin.getPumpStatus("communication-loss regression")
            assertThat(status.lastConnection).isEqualTo(lastGoodCommunication)
            assertThat(status.previousConnection).isEqualTo(lastGoodCommunication - 60_000L)
        }

        verify(uiComm, times(2)).executeCommand(MedtronicCommandType.GetBatteryStatus)
        verify(communicationManager, never()).isDeviceReachable()
    }

    @Test
    fun `initialization without successful replies retains last successful communication time`() {
        // Exercise the terminal initialization path, below the five-error tuning branch.
        whenever(uiComm.invalidResponsesCount).thenReturn(4)
        for (command in listOf(
            MedtronicCommandType.GetRealTimeClock,
            MedtronicCommandType.GetRemainingInsulin,
            MedtronicCommandType.GetBatteryStatus,
            MedtronicCommandType.Settings,
            MedtronicCommandType.GetBasalProfileSTD
        )) {
            whenever(uiComm.executeCommand(command)).thenReturn(errorTask(command))
        }
        // getPumpHistory returns a nonnull, possibly empty result even when no page was received.
        val historyFailure = errorTask(MedtronicCommandType.GetHistoryData).also {
            it.result = PumpHistoryResult(aapsLogger, null, null)
            // UITask currently classifies every nonnull history wrapper as Data.
            it.responseType = MedtronicUIResponseType.Data
        }
        whenever(uiComm.executeCommand(eq(MedtronicCommandType.GetHistoryData), anyOrNull()))
            .thenReturn(historyFailure)

        plugin.getPumpStatus("initialization communication-loss regression")

        assertThat(status.lastConnection).isEqualTo(lastGoodCommunication)
        assertThat(status.previousConnection).isEqualTo(lastGoodCommunication - 60_000L)
        verify(uiComm).executeCommand(MedtronicCommandType.GetRemainingInsulin)
        verify(uiComm).executeCommand(MedtronicCommandType.GetBatteryStatus)
        verify(uiComm).executeCommand(MedtronicCommandType.Settings)
        verify(uiComm, times(2)).executeCommand(MedtronicCommandType.GetBasalProfileSTD)
        verify(communicationManager, never()).isDeviceReachable()
        verify(serviceTaskExecutor, never()).startTask(org.mockito.kotlin.any())
    }

    private fun errorTask(command: MedtronicCommandType) =
        MedtronicUITask(rxBus, aapsLogger, status, medtronicUtil).with(command, null).also {
            it.responseType = MedtronicUIResponseType.Error
            it.errorDescription = "No radio response"
        }

    @Suppress("UNCHECKED_CAST")
    private fun scheduleBatteryPollNow() {
        val field = PumpPluginAbstract::class.java.getDeclaredField("statusRefreshMap").also { it.isAccessible = true }
        val schedule = field.get(plugin) as MutableMap<PumpDataRefreshType?, Long?>
        schedule.clear()
        schedule[PumpDataRefreshType.BatteryStatus] = System.currentTimeMillis() - 60_000L
    }

    private fun setPluginField(name: String, value: Any) {
        MedtronicPumpPlugin::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(plugin, value)
    }

    private fun setBaseField(name: String, value: Any) {
        PumpPluginAbstract::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(plugin, value)
    }
}
