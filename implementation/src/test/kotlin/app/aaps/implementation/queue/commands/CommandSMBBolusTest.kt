package app.aaps.implementation.queue.commands

import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.pump.PumpEnactResultObject
import com.google.common.truth.Truth.assertThat
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import javax.inject.Provider

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandSMBBolusTest {

    @Mock lateinit var aapsLogger: AAPSLogger
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var pump: Pump

    private lateinit var pumpResult: PumpEnactResult

    private val injector = HasAndroidInjector {
        AndroidInjector { instance ->
            (instance as CommandSMBBolus).apply {
                aapsLogger = this@CommandSMBBolusTest.aapsLogger
                rh = this@CommandSMBBolusTest.rh
                dateUtil = this@CommandSMBBolusTest.dateUtil
                activePlugin = this@CommandSMBBolusTest.activePlugin
                persistenceLayer = this@CommandSMBBolusTest.persistenceLayer
                preferences = this@CommandSMBBolusTest.preferences
                pumpEnactResultProvider = Provider { PumpEnactResultObject(rh) }
            }
        }
    }

    private class RecordingCallback : Callback() {
        var calls = 0

        override fun run() {
            calls++
        }
    }

    @BeforeEach
    fun prepare() {
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(preferences.get(IntKey.ApsMaxSmbFrequency)).thenReturn(FREQUENCY_MINUTES)
        whenever(activePlugin.activePump).thenReturn(pump)
        pumpResult = PumpEnactResultObject(rh).success(true).enacted(true).bolusDelivered(0.1)
        whenever(pump.deliverTreatment(any())).thenReturn(pumpResult)
        BolusProgressData.bolusEnded = false
    }

    @Test
    fun freshRequestPreservesAmountAndPumpResultUsingOneClockReading() {
        val lastBolusTime = NOW - T.mins(FREQUENCY_MINUTES.toLong()).msecs()
        whenever(persistenceLayer.getNewestBolus()).thenReturn(bolus(lastBolusTime))
        // This clock deliberately differs from wall time. A second read would expire the request.
        whenever(dateUtil.now()).thenReturn(NOW, NOW + T.mins(1).msecs())
        val request = request(lastKnownBolusTime = lastBolusTime)

        val callback = execute(request)

        verify(pump).deliverTreatment(same(request))
        verify(dateUtil, times(1)).now()
        assertThat(request.insulin).isEqualTo(0.1)
        assertThat(callback.result).isSameInstanceAs(pumpResult)
        assertThat(callback.calls).isEqualTo(1)
        assertThat(BolusProgressData.bolusEnded).isTrue()
    }

    @Test
    fun nonPositiveOrNonFiniteInsulinNeverReachesPump() {
        val invalidAmounts = doubleArrayOf(0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        invalidAmounts.forEach { amount ->
            val callback = execute(request().apply { insulin = amount })

            assertRejected(callback, "SMB request has invalid insulin amount")
        }

        verify(pump, never()).deliverTreatment(any())
    }

    @Test
    fun bolusDiscoveredAfterEnqueueIsRejectedInsideFrequencyWindow() {
        assertChangedHistoryRejected(NOW - T.mins(1).msecs())
    }

    @Test
    fun bolusDiscoveredAfterEnqueueIsRejectedOutsideFrequencyWindow() {
        // Frequency alone would permit this newly discovered bolus.
        assertChangedHistoryRejected(NOW - T.mins(4).msecs())
    }

    @Test
    fun frequencyRejectsLastMillisecondButAllowsExactBoundaryAndLater() {
        val boundary = NOW - T.mins(FREQUENCY_MINUTES.toLong()).msecs()
        listOf(1L, 0L, -1L).forEach { offset ->
            clearInvocations(pump)
            val lastBolusTime = boundary + offset
            whenever(persistenceLayer.getNewestBolus()).thenReturn(bolus(lastBolusTime))
            val request = request(lastKnownBolusTime = lastBolusTime)

            val callback = execute(request)

            if (offset > 0) {
                verify(pump, never()).deliverTreatment(any())
                assertRejected(callback, "SMB requested but still in $FREQUENCY_MINUTES min interval")
            } else {
                verify(pump).deliverTreatment(same(request))
                assertThat(callback.result).isSameInstanceAs(pumpResult)
                assertThat(callback.calls).isEqualTo(1)
            }
        }
    }

    @Test
    fun requestExpiresAtExactlySixtySeconds() {
        listOf(59_999L, 60_000L, 60_001L).forEach { age ->
            clearInvocations(pump)
            val request = request(deliverAt = NOW - age)

            val callback = execute(request)

            if (age < T.mins(1).msecs()) {
                verify(pump).deliverTreatment(same(request))
                assertThat(callback.result).isSameInstanceAs(pumpResult)
            } else {
                verify(pump, never()).deliverTreatment(any())
                assertRejected(callback, "SMB request too old")
            }
            assertThat(callback.calls).isEqualTo(1)
        }
    }

    @Test
    fun missingDeliveryTimestampNeverReachesPump() {
        val callback = execute(request(deliverAt = 0L))

        verify(pump, never()).deliverTreatment(any())
        assertRejected(callback, "SMB request too old")
    }

    @Test
    fun historyLookupDelayDoesNotExtendRequestLifetime() {
        var clock = NOW
        whenever(dateUtil.now()).thenAnswer { clock }
        whenever(persistenceLayer.getNewestBolus()).thenAnswer {
            clock += T.mins(1).msecs()
            null
        }

        val callback = execute(request())

        verify(pump, never()).deliverTreatment(any())
        verify(dateUtil, times(1)).now()
        assertRejected(callback, "SMB request too old")
    }

    private fun assertChangedHistoryRejected(newestBolusTime: Long) {
        val request = request(lastKnownBolusTime = NOW - T.mins(10).msecs())
        whenever(persistenceLayer.getNewestBolus()).thenReturn(bolus(newestBolusTime))

        val callback = execute(request)

        verify(pump, never()).deliverTreatment(any())
        assertRejected(callback, "Rejecting bolus, another bolus was issued since request time")
    }

    private fun execute(request: DetailedBolusInfo): RecordingCallback = RecordingCallback().also { callback ->
        CommandSMBBolus(injector, request, callback).execute()
    }

    private fun assertRejected(callback: RecordingCallback, comment: String) {
        assertThat(callback.result.success).isFalse()
        assertThat(callback.result.enacted).isFalse()
        assertThat(callback.result.comment).isEqualTo(comment)
        assertThat(callback.calls).isEqualTo(1)
        assertThat(BolusProgressData.bolusEnded).isTrue()
    }

    private fun request(lastKnownBolusTime: Long = 0L, deliverAt: Long = NOW) = DetailedBolusInfo().apply {
        insulin = 0.1
        bolusType = BS.Type.SMB
        this.lastKnownBolusTime = lastKnownBolusTime
        deliverAtTheLatest = deliverAt
    }

    private fun bolus(timestamp: Long) = BS(timestamp = timestamp, amount = 0.1, type = BS.Type.NORMAL)

    companion object {
        private const val NOW = 1_672_531_200_000L
        private const val FREQUENCY_MINUTES = 3
    }
}
