package app.aaps.implementation.wizard

import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class BolusWizardTest : TestBaseWithProfile() {

    private val pumpBolusStep = 0.1

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var loop: Loop
    @Mock lateinit var autosensDataStore: AutosensDataStore
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var automation: Automation
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer

    @BeforeEach
    fun prepare() {
        whenever(activePlugin.activeAPS).thenReturn(openAPSSMBPlugin)
    }

    @Suppress("SameParameterValue")
    private fun setupProfile(targetLow: Double, targetHigh: Double, insulinSensitivityFactor: Double, insulinToCarbRatio: Double): Profile {
        val profile: Profile = mock()
        whenever(profile.getTargetLowMgdl()).thenReturn(targetLow)
        whenever(profile.getTargetHighMgdl()).thenReturn(targetHigh)
        whenever(profile.getIsfMgdlForCarbs(any(), any(), any(), any())).thenReturn(insulinSensitivityFactor)
        whenever(profile.getIc()).thenReturn(insulinToCarbRatio)

        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(System.currentTimeMillis()))
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(System.currentTimeMillis()))
        testPumpPlugin.pumpDescription = PumpDescription().also {
            it.bolusStep = pumpBolusStep
        }
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)

        doAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<Constraint<Double>>(0)
        }.whenever(constraintChecker).applyBolusConstraints(anyOrNull())
        return profile
    }

    private fun calculate(
        profile: Profile,
        carbs: Int = 30,
        bg: Double = 100.0,
        includeBolusIOB: Boolean = true,
        includeBasalIOB: Boolean = true
    ): BolusWizard =
        BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData
        ).doCalc(
            profile = profile,
            profileName = "Loaded profile",
            tempTarget = null,
            carbs = carbs,
            cob = 0.0,
            bg = bg,
            correction = 0.0,
            percentageCorrection = 100,
            useBg = true,
            useCob = false,
            includeBolusIOB = includeBolusIOB,
            includeBasalIOB = includeBasalIOB,
            useSuperBolus = false,
            useTT = false,
            useTrend = false,
            useAlarm = false
        )

    @Test
    fun shouldCalculateTheSameBolusWhenBGsInRange() {
        val profile = setupProfile(
            targetLow = 80.0, targetHigh = 140.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 12.0
        )

        val lowerBg = calculate(profile = profile, carbs = 24, bg = 90.0)
        val higherBg = calculate(profile = profile, carbs = 24, bg = 120.0)

        assertThat(lowerBg.insulinFromBG).isEqualTo(0.0)
        assertThat(higherBg.insulinFromBG).isEqualTo(0.0)
        assertThat(higherBg.calculatedTotalInsulin).isWithin(0.01).of(lowerBg.calculatedTotalInsulin)
    }

    @Test
    fun shouldCalculateHigherBolusWhenHighBG() {
        val profile = setupProfile(
            targetLow = 80.0, targetHigh = 140.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 12.0
        )

        val highBg = calculate(profile = profile, carbs = 24, bg = 190.0)
        val inRange = calculate(profile = profile, carbs = 24, bg = 100.0)

        assertThat(highBg.calculatedTotalInsulin).isGreaterThan(inRange.calculatedTotalInsulin)
    }

    @Test
    fun shouldCalculateLowerBolusWhenLowBG() {
        val profile = setupProfile(
            targetLow = 80.0, targetHigh = 140.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 12.0
        )

        val lowBg = calculate(profile = profile, carbs = 24, bg = 65.0)
        val inRange = calculate(profile = profile, carbs = 24, bg = 100.0)

        assertThat(lowBg.calculatedTotalInsulin).isLessThan(inRange.calculatedTotalInsulin)
    }

    @Test
    fun usesLoadedProfileCarbRatioAndSensitivityForMealAndGlucoseCorrection() {
        val profile = setupProfile(
            targetLow = 90.0, targetHigh = 110.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 10.0
        )
        val recommendation = calculate(profile = profile, carbs = 30, bg = 160.0)

        assertThat(recommendation.ic).isEqualTo(10.0)
        assertThat(recommendation.sens).isEqualTo(50.0)
        assertThat(recommendation.insulinFromCarbs).isWithin(0.01).of(3.0)
        assertThat(recommendation.insulinFromBG).isWithin(0.01).of(1.0)
        assertThat(recommendation.calculatedTotalInsulin).isWithin(0.01).of(4.0)

        whenever(profile.getIc()).thenReturn(15.0)
        val changedCarbRatio = calculate(profile = profile, carbs = 30, bg = 160.0)
        assertThat(changedCarbRatio.calculatedTotalInsulin).isWithin(0.01).of(3.0)

        whenever(profile.getIsfMgdlForCarbs(any(), any(), any(), any())).thenReturn(100.0)
        val changedSensitivity = calculate(profile = profile, carbs = 30, bg = 160.0)
        assertThat(changedSensitivity.calculatedTotalInsulin).isWithin(0.01).of(2.5)
    }

    @Test
    fun accountsForBolusAndBasalActiveInsulinWhenEnabled() {
        val profile = setupProfile(
            targetLow = 90.0, targetHigh = 110.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 10.0
        )
        val now = dateUtil.now()
        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(now, iob = 0.8))
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended())
            .thenReturn(IobTotal(now, basaliob = 0.4))

        val bothIncluded = calculate(profile = profile, carbs = 30, bg = 160.0)
        assertThat(bothIncluded.insulinFromBolusIOB).isWithin(0.01).of(0.8)
        assertThat(bothIncluded.insulinFromBasalIOB).isWithin(0.01).of(0.4)
        assertThat(bothIncluded.calculatedTotalInsulin).isWithin(0.01).of(2.8)

        val basalOnly = calculate(profile = profile, carbs = 30, bg = 160.0, includeBolusIOB = false)
        assertThat(basalOnly.calculatedTotalInsulin).isWithin(0.01).of(3.6)
        val bolusOnly = calculate(profile = profile, carbs = 30, bg = 160.0, includeBasalIOB = false)
        assertThat(bolusOnly.calculatedTotalInsulin).isWithin(0.01).of(3.2)
        val neitherIncluded = calculate(
            profile = profile, carbs = 30, bg = 160.0, includeBolusIOB = false, includeBasalIOB = false
        )
        assertThat(neitherIncluded.calculatedTotalInsulin).isWithin(0.01).of(4.0)
    }

    @Test
    fun doesNotAddScheduledBasalToMealBolusWhenSuperBolusIsOff() {
        val profile = setupProfile(
            targetLow = 90.0, targetHigh = 110.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 10.0
        )
        whenever(profile.getBasal()).thenReturn(0.6)
        whenever(profile.getBasal(any())).thenReturn(0.6)
        val lowerBasal = calculate(profile = profile, carbs = 30, bg = 100.0)

        whenever(profile.getBasal()).thenReturn(1.2)
        whenever(profile.getBasal(any())).thenReturn(1.2)
        val higherBasal = calculate(profile = profile, carbs = 30, bg = 100.0)

        assertThat(lowerBasal.insulinFromSuperBolus).isEqualTo(0.0)
        assertThat(higherBasal.insulinFromSuperBolus).isEqualTo(0.0)
        assertThat(lowerBasal.calculatedTotalInsulin).isWithin(0.01).of(3.0)
        assertThat(higherBasal.calculatedTotalInsulin).isWithin(0.01).of(lowerBasal.calculatedTotalInsulin)
    }

    @Test
    fun retainsConfiguredBolusConstraintsOnProfileRecommendation() {
        val profile = setupProfile(
            targetLow = 90.0, targetHigh = 110.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 10.0
        )
        doAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<Constraint<Double>>(0)
                .setIfSmaller(2.5, "Configured maximum bolus", constraintChecker)
        }.whenever(constraintChecker).applyBolusConstraints(anyOrNull())

        val recommendation = calculate(profile = profile, carbs = 30, bg = 160.0)

        assertThat(recommendation.calculatedTotalInsulin).isWithin(0.01).of(4.0)
        assertThat(recommendation.insulinAfterConstraints).isWithin(0.01).of(2.5)
    }

    @Test
    fun calculatingMealRecommendationDoesNotSendTreatmentToPump() {
        val profile = setupProfile(
            targetLow = 90.0, targetHigh = 110.0, insulinSensitivityFactor = 50.0, insulinToCarbRatio = 10.0
        )

        val recommendation = calculate(profile = profile, carbs = 30, bg = 160.0)

        assertThat(recommendation.insulinAfterConstraints).isWithin(0.01).of(4.0)
        verifyNoInteractions(commandQueue)
    }
}
