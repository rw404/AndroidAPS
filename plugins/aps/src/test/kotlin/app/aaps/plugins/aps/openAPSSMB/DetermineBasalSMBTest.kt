package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import kotlin.math.round

/** Synthetic regression cases for existing SMB safeguards; these are not patient profiles. */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DetermineBasalSMBTest {

    @Mock lateinit var profileUtil: ProfileUtil
    @Mock lateinit var fabricPrivacy: FabricPrivacy
    private lateinit var determineBasal: DetermineBasalSMB

    private val now = 1_700_000_000_000L

    @BeforeEach
    fun prepare() {
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToStringInUnits(anyOrNull(), any())).thenAnswer { it.getArgument<Double?>(0)?.toString().orEmpty() }
        determineBasal = DetermineBasalSMB(profileUtil, fabricPrivacy)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 1.0, 2.0, 2.5])
    fun `eligible high glucose produces a bounded SMB including whole number deltas`(delta: Double) {
        val result = calculate(glucose = glucose(delta = delta))

        val units = requireNotNull(result.units)
        assertThat(units).isGreaterThan(0.0)
        assertThat(units).isAtMost(0.5)
        assertThat(units).isAtMost(requireNotNull(result.insulinReq) / 2)
        assertThat(result.deliverAt).isEqualTo(now)
    }

    @Test
    fun `SMB permission gate overrides always enabled preference`() {
        assertThat(calculate(microBolusAllowed = false).units).isNull()
    }

    @Test
    fun `high temporary target overrides always enabled preference`() {
        val profile = profile().copy(min_bg = 140.0, max_bg = 140.0, target_bg = 140.0, temptargetSet = true)

        val result = calculate(profile = profile)

        assertThat(result.units).isNull()
        assertThat(result.consoleError).contains("SMB disabled due to high temptarget of 140.0")
    }

    @ParameterizedTest
    @ValueSource(longs = [13 * 60_000L, -6 * 60_000L])
    fun `stale and future CGM data suppress SMB and replace a high temp`(ageMillis: Long) {
        val result = calculate(glucose = glucose().copy(date = now - ageMillis), currentTemp = CurrentTemp(60, 2.0, null))

        assertThat(result.units).isNull()
        assertThat(result.rate).isEqualTo(1.0)
        assertThat(result.duration).isEqualTo(30)
    }

    @ParameterizedTest
    @ValueSource(doubles = [9.0, 38.0])
    fun `CGM error values suppress SMB`(bg: Double) {
        assertThat(calculate(glucose = glucose().copy(glucose = bg)).units).isNull()
    }

    @Test
    fun `noisy CGM suppresses SMB and shortens an existing zero temp`() {
        val result = calculate(glucose = glucose().copy(noise = 3.0), currentTemp = CurrentTemp(90, 0.0, null))

        assertThat(result.units).isNull()
        assertThat(result.rate).isEqualTo(0.0)
        assertThat(result.duration).isEqualTo(30)
    }

    @Test
    fun `flat CGM suppresses SMB`() {
        assertThat(calculate(flatBGsDetected = true).units).isNull()
    }

    @Test
    fun `low glucose suspends basal without a microbolus`() {
        val result = calculate(glucose = glucose().copy(glucose = 60.0))

        assertThat(result.units).isNull()
        assertThat(result.rate).isEqualTo(0.0)
        assertThat(requireNotNull(result.duration)).isAtLeast(30)
        assertThat(requireNotNull(result.duration)).isAtMost(120)
    }

    @Test
    fun `predicted low glucose disables SMB even when current glucose is high`() {
        val result = calculate(activity = 0.03)

        assertThat(result.units).isNull()
        assertThat(result.rate).isEqualTo(0.0)
        assertThat(result.consoleError.orEmpty().any { it.contains("disabling SMB") }).isTrue()
    }

    @Test
    fun `large glucose jump disables SMB`() {
        val result = calculate(glucose = glucose(delta = 41.0))

        assertThat(result.units).isNull()
        assertThat(result.reason.toString()).contains("SMB disabled")
    }

    @Test
    fun `IOB above its limit prevents a microbolus`() {
        assertThat(calculate(iob = 3.01).units).isNull()
    }

    @Test
    fun `microbolus respects remaining IOB budget`() {
        val result = calculate(profile = profile().copy(max_iob = 0.37))

        val units = requireNotNull(result.units)
        assertThat(units).isGreaterThan(0.0)
        assertThat(units).isAtMost(0.37 / 2)
        assertThat(units).isAtMost(requireNotNull(result.insulinReq) / 2)
    }

    @Test
    fun `sub increment insulin requirement produces no microbolus`() {
        assertThat(calculate(profile = profile().copy(max_iob = 0.09, bolus_increment = 0.05)).units).isNull()
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.05, 0.1, 0.25])
    fun `microbolus rounds down to the pump increment`(increment: Double) {
        val result = calculate(profile = profile().copy(max_iob = 0.93, bolus_increment = increment))

        val units = requireNotNull(result.units)
        val steps = units / increment
        assertThat(steps).isWithin(1e-9).of(round(steps))
        assertThat(units).isAtMost(requireNotNull(result.insulinReq) / 2)
    }

    @Test
    fun `SMB basal cap and UAM basal cap are respected independently`() {
        val profile = profile().copy(maxSMBBasalMinutes = 12, maxUAMSMBBasalMinutes = 6)
        val smb = calculate(profile = profile)
        val uam = calculate(profile = profile, iob = 0.1)

        assertThat(requireNotNull(smb.units)).isAtMost(0.2)
        assertThat(requireNotNull(uam.units)).isAtMost(0.1)
        assertThat(requireNotNull(smb.units)).isGreaterThan(requireNotNull(uam.units))
    }

    @Test
    fun `recent or future bolus prevents another SMB`() {
        assertThat(calculate(lastBolusAgeMillis = 60_000L).units).isNull()
        assertThat(calculate(lastBolusAgeMillis = -60_000L).units).isNull()
    }

    @Test
    fun `SMB interval preserves the existing strict six second tolerance boundary`() {
        assertThat(calculate(lastBolusAgeMillis = 174_000L).units).isNull()
        assertThat(requireNotNull(calculate(lastBolusAgeMillis = 174_001L).units)).isGreaterThan(0.0)
    }

    @Test
    fun `SMB interval is clamped to existing one to ten minute bounds`() {
        assertThat(calculate(profile = profile().copy(SMBInterval = 0), lastBolusAgeMillis = 54_000L).units).isNull()
        assertThat(requireNotNull(calculate(profile = profile().copy(SMBInterval = 0), lastBolusAgeMillis = 54_001L).units)).isGreaterThan(0.0)
        assertThat(calculate(profile = profile().copy(SMBInterval = 11), lastBolusAgeMillis = 594_000L).units).isNull()
        assertThat(requireNotNull(calculate(profile = profile().copy(SMBInterval = 11), lastBolusAgeMillis = 594_001L).units)).isGreaterThan(0.0)
    }

    private fun calculate(
        glucose: GlucoseStatusSMB = glucose(),
        currentTemp: CurrentTemp = CurrentTemp(0, 1.0, null),
        profile: OapsProfile = profile(),
        microBolusAllowed: Boolean = true,
        flatBGsDetected: Boolean = false,
        iob: Double = 0.0,
        activity: Double = 0.0,
        lastBolusAgeMillis: Long = 10 * 60_000L
    ): RT = determineBasal.determine_basal(
        glucose_status = glucose,
        currenttemp = currentTemp,
        iob_data_array = Array(48) { index ->
            IobTotal(
                time = now + index * 5 * 60_000L,
                iob = iob,
                activity = activity,
                lastBolusTime = now - lastBolusAgeMillis,
                iobWithZeroTemp = IobTotal(time = now + index * 5 * 60_000L, activity = activity)
            )
        },
        profile = profile,
        autosens_data = AutosensResult(ratio = 1.0),
        meal_data = MealData(lastCarbTime = now),
        microBolusAllowed = microBolusAllowed,
        currentTime = now,
        flatBGsDetected = flatBGsDetected,
        dynIsfMode = false
    )

    private fun glucose(delta: Double = 1.0) = GlucoseStatusSMB(
        glucose = 200.0,
        delta = delta,
        shortAvgDelta = delta,
        longAvgDelta = delta,
        date = now
    )

    private fun profile() = OapsProfile(
        dia = 5.0,
        min_5m_carbimpact = 0.0,
        max_iob = 3.0,
        max_daily_basal = 1.0,
        max_basal = 3.0,
        min_bg = 100.0,
        max_bg = 100.0,
        target_bg = 100.0,
        carb_ratio = 10.0,
        sens = 50.0,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 3.0,
        current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 90,
        enableUAM = true,
        A52_risk_enable = false,
        SMBInterval = 3,
        enableSMB_with_COB = false,
        enableSMB_with_temptarget = false,
        allowSMB_with_high_temptarget = false,
        enableSMB_always = true,
        enableSMB_after_carbs = false,
        maxSMBBasalMinutes = 30,
        maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.05,
        carbsReqThreshold = 1,
        current_basal = 1.0,
        temptargetSet = false,
        autosens_max = 1.2,
        out_units = "mg/dl",
        lgsThreshold = null,
        variable_sens = 0.0,
        insulinDivisor = 0,
        TDD = 0.0
    )
}
