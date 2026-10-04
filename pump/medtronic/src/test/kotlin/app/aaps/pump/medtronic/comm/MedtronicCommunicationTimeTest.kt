package app.aaps.pump.medtronic.comm

import app.aaps.pump.common.hw.rileylink.ble.RFSpy
import app.aaps.pump.common.hw.rileylink.ble.data.RFSpyResponse
import app.aaps.pump.common.hw.rileylink.ble.data.RadioResponse
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkLongKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import app.aaps.pump.common.hw.rileylink.service.tasks.WakeAndTuneTask
import app.aaps.pump.medtronic.MedtronicPumpPlugin
import app.aaps.pump.medtronic.MedtronicTestBase
import app.aaps.pump.medtronic.comm.ui.MedtronicUIPostprocessor
import app.aaps.pump.medtronic.comm.ui.MedtronicUITask
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.defs.MedtronicDeviceType
import app.aaps.pump.medtronic.defs.MedtronicUIResponseType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

class MedtronicCommunicationTimeTest : MedtronicTestBase() {

    @Mock lateinit var pumpPlugin: MedtronicPumpPlugin
    @Mock lateinit var converter: MedtronicConverter
    @Mock lateinit var rfSpy: RFSpy
    @Mock lateinit var rfResponse: RFSpyResponse
    @Mock lateinit var radioResponse: RadioResponse
    @Mock lateinit var serviceTaskExecutor: ServiceTaskExecutor
    @Mock lateinit var postprocessor: MedtronicUIPostprocessor

    private lateinit var status: MedtronicPumpStatus
    private lateinit var communicationManager: MedtronicCommunicationManager

    @BeforeEach
    fun setup() {
        status = spy(MedtronicPumpStatus(preferences, rxBus, rileyLinkUtil))
        status.lastConnection = 1_000L
        status.lastDataTime = 1_000L
        // Exercise the real pump callback reached by the transport without starting Android services.
        MedtronicPumpPlugin::class.java.getDeclaredField("medtronicPumpStatus").also {
            it.isAccessible = true
            it.set(pumpPlugin, status)
        }
        doCallRealMethod().whenever(pumpPlugin).setLastCommunicationToNow()
        whenever(activePlugin.activePump).thenReturn(pumpPlugin)
        whenever(pumpPlugin.lastConnectionTimeMillis).thenReturn(1_000L)
        whenever(medtronicUtil.medtronicPumpModel).thenReturn(MedtronicDeviceType.Medtronic_722)
        val serviceData = RileyLinkServiceData(aapsLogger, rileyLinkUtil, rxBus).apply {
            targetDevice = RileyLinkTargetDevice.MedtronicPump
        }
        communicationManager = MedtronicCommunicationManager(
            status, pumpPlugin, converter, medtronicUtil, decoder, aapsLogger, preferences,
            serviceData, serviceTaskExecutor, rfSpy, activePlugin, rileyLinkUtil,
            Provider { mock<WakeAndTuneTask>() }, Provider { radioResponse }
        )
        communicationManager.setDoWakeUpBeforeCommand(false)
        whenever(rfSpy.transmitThenReceive(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(rfResponse)
        whenever(rfResponse.getRadioResponse()).thenReturn(radioResponse)
    }

    private fun timeoutResponses() {
        whenever(radioResponse.getPayload()).thenReturn(byteArrayOf())
        whenever(rfResponse.wasTimeout()).thenReturn(true)
    }

    @Test
    fun `radio timeouts during status polling preserve last contact`() {
        timeoutResponses()

        assertThat(communicationManager.getRemainingInsulin()).isNull()

        assertThat(status.lastConnection).isEqualTo(1_000L)
        assertThat(status.lastDataTime).isEqualTo(1_000L)
        verify(pumpPlugin, never()).setLastCommunicationToNow()
        verify(preferences, never()).put(any<RileyLinkLongKey>(), any<Long>())
    }

    @Test
    fun `history timeout wrapper cannot reset contact during post processing`() {
        timeoutResponses()
        val task = MedtronicUITask(rxBus, aapsLogger, status, medtronicUtil)
            .with(MedtronicCommandType.GetHistoryData, null).apply {
                parameters = listOf(null, null)
            }

        task.execute(communicationManager)
        task.postProcess(postprocessor)

        assertThat(task.result).isNotNull()
        assertThat(task.responseType).isEqualTo(MedtronicUIResponseType.Data)
        assertThat(status.lastConnection).isEqualTo(1_000L)
        assertThat(status.lastDataTime).isEqualTo(1_000L)
        verify(status, never()).setLastCommunicationToNow()
        verify(pumpPlugin, never()).setLastCommunicationToNow()
    }

    @Test
    fun `validated pump response updates actual pump contact and persisted time`() {
        whenever(radioResponse.getPayload()).thenReturn(
            byteArrayOf(0xa7.toByte(), 0, 0, 0, MedtronicCommandType.GetRemainingInsulin.commandCode, 2, 0, 100)
        )
        whenever(converter.decodeRemainingInsulin(any())).thenReturn(10.0)
        val beforeResponse = System.currentTimeMillis()

        assertThat(communicationManager.getRemainingInsulin()).isEqualTo(10.0)

        assertThat(status.lastConnection).isAtLeast(beforeResponse)
        assertThat(status.lastDataTime).isAtLeast(beforeResponse)
        verify(pumpPlugin).setLastCommunicationToNow()
        verify(status).setLastCommunicationToNow()
        verify(preferences).put(org.mockito.kotlin.eq(RileyLinkLongKey.LastGoodDeviceCommunicationTime), any<Long>())
    }
}
