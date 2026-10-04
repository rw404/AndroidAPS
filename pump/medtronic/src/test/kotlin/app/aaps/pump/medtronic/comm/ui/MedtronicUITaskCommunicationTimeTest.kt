package app.aaps.pump.medtronic.comm.ui

import app.aaps.pump.medtronic.MedtronicTestBase
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.defs.MedtronicUIResponseType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MedtronicUITaskCommunicationTimeTest : MedtronicTestBase() {

    @Mock lateinit var communicationManager: MedtronicCommunicationManager
    @Mock lateinit var postprocessor: MedtronicUIPostprocessor
    private lateinit var status: MedtronicPumpStatus

    @BeforeEach
    fun setup() {
        status = spy(MedtronicPumpStatus(preferences, rxBus, rileyLinkUtil))
        status.lastConnection = 1_000L
        status.lastDataTime = 1_000L
    }

    @Test
    fun `false command result without a radio response preserves last contact`() {
        whenever(communicationManager.setBolus(1.0)).thenReturn(false)
        val task = MedtronicUITask(rxBus, aapsLogger, status, medtronicUtil)
            .with(MedtronicCommandType.SetBolus, listOf(1.0))

        task.execute(communicationManager)
        task.postProcess(postprocessor)

        assertThat(task.result).isEqualTo(false)
        assertThat(task.responseType).isEqualTo(MedtronicUIResponseType.Data)
        assertThat(status.lastConnection).isEqualTo(1_000L)
        assertThat(status.lastDataTime).isEqualTo(1_000L)
        verify(status, never()).setLastCommunicationToNow()
        verify(postprocessor).postProcessData(task)
    }

    @Test
    fun `failed status query preserves last contact`() {
        whenever(communicationManager.getRemainingInsulin()).thenReturn(null)
        val task = MedtronicUITask(rxBus, aapsLogger, status, medtronicUtil)
            .with(MedtronicCommandType.GetRemainingInsulin, null)

        task.execute(communicationManager)
        task.postProcess(postprocessor)

        assertThat(task.responseType).isEqualTo(MedtronicUIResponseType.Error)
        assertThat(status.lastConnection).isEqualTo(1_000L)
        verify(status, never()).setLastCommunicationToNow()
    }
}
