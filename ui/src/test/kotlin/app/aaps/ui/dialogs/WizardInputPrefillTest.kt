package app.aaps.ui.dialogs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WizardInputPrefillTest {

    @Test
    fun `new dialog receives the requested food and note`() {
        assertEquals(WizardInputPrefill(28.0, "Food entry"), resolveWizardInputPrefill(28.0, "Food entry", null, null))
    }

    @Test
    fun `recreated dialog uses edited values instead of original food arguments`() {
        assertEquals(WizardInputPrefill(19.0, "Measured portion"), resolveWizardInputPrefill(28.0, "Food entry", 19.0, "Measured portion"))
    }

    @Test
    fun `cleared carbohydrates and notes stay cleared after recreation`() {
        assertEquals(WizardInputPrefill(0.0, ""), resolveWizardInputPrefill(28.0, "Food entry", 0.0, ""))
    }

    @Test
    fun `missing saved fields independently fall back to arguments`() {
        assertEquals(WizardInputPrefill(19.0, "Food entry"), resolveWizardInputPrefill(28.0, "Food entry", 19.0, null))
        assertEquals(WizardInputPrefill(28.0, "Measured portion"), resolveWizardInputPrefill(28.0, "Food entry", null, "Measured portion"))
    }

    @Test
    fun `dialog without any prefill starts with empty inputs`() {
        assertEquals(WizardInputPrefill(0.0, ""), resolveWizardInputPrefill(null, null, null, null))
    }
}
