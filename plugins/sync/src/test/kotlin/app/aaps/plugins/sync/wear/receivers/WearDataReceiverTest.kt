package app.aaps.plugins.sync.wear.receivers

import android.content.Intent
import android.os.Bundle
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class WearDataReceiverTest : TestBaseWithProfile() {

    @Mock lateinit var forwardedEvents: RxBus

    private lateinit var receiver: WearDataReceiver
    private val payload = EventData.SingleBg(dataset = 1, timeStamp = 1_000L, sgv = 120.0, high = 180.0, low = 70.0)

    init {
        addInjector {
            if (it is WearDataReceiver) {
                it.aapsLogger = aapsLogger
                it.config = config
                it.rxBus = forwardedEvents
            }
        }
    }

    @BeforeEach
    fun prepare() {
        receiver = WearDataReceiver()
        whenever(config.APS).thenReturn(true)
    }

    @Test
    fun healfiAcceptsItsOwnDatasetBroadcast() {
        configureHealfi()

        receiver.onReceive(context, datasetIntent("app.healfi.androidaps.weardata"))

        val events = argumentCaptor<EventMobileToWear>()
        verify(forwardedEvents).send(events.capture())
        assertThat(events.singleValue.payload).isEqualTo(payload)
    }

    @Test
    fun healfiIgnoresLegacyAapsDatasetBroadcast() {
        configureHealfi()

        receiver.onReceive(context, datasetIntent(Intents.AAPS_CLIENT_WEAR_DATA))

        verifyNoInteractions(forwardedEvents)
    }

    @Test
    fun existingAapsKeepsReceivingLegacyDatasetBroadcast() {
        configureAaps()

        receiver.onReceive(context, datasetIntent(Intents.AAPS_CLIENT_WEAR_DATA))

        val events = argumentCaptor<EventMobileToWear>()
        verify(forwardedEvents).send(events.capture())
        assertThat(events.singleValue.payload).isEqualTo(payload)
    }

    @Test
    fun existingAapsIgnoresHealfiDatasetBroadcast() {
        configureAaps()

        receiver.onReceive(context, datasetIntent("app.healfi.androidaps.weardata"))

        verifyNoInteractions(forwardedEvents)
    }

    @Test
    fun healfiDatasetRouteDoesNotForwardPumpCommands() {
        configureHealfi()

        receiver.onReceive(context, datasetIntent("app.healfi.androidaps.weardata", EventData.CancelBolus(1_000L)))

        verifyNoInteractions(forwardedEvents)
    }

    private fun configureHealfi() {
        whenever(config.FLAVOR).thenReturn("healfi")
        whenever(config.APPLICATION_ID).thenReturn("app.healfi.androidaps")
    }

    private fun configureAaps() {
        whenever(config.FLAVOR).thenReturn("full")
        whenever(config.APPLICATION_ID).thenReturn("info.nightscout.androidaps")
    }

    private fun datasetIntent(action: String, event: EventData = payload): Intent {
        val bundle = mock<Bundle>()
        whenever(bundle.keySet()).thenReturn(emptySet())
        whenever(bundle.getInt(WearDataReceiver.CLIENT)).thenReturn(1)
        whenever(bundle.getString(WearDataReceiver.DATA)).thenReturn(event.serialize())
        return mock<Intent>().also {
            whenever(it.action).thenReturn(action)
            whenever(it.extras).thenReturn(bundle)
        }
    }
}
