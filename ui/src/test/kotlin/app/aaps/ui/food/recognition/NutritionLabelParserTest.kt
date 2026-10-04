package app.aaps.ui.food.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NutritionLabelParserTest {

    @Test
    fun `Russian label distinguishes carbs from sugars and net package weight`() {
        val result = NutritionLabelParser.parse("Название: Йогурт\nМасса нетто 180 г\nПищевая ценность на 100 г\nУглеводы 8,5 г\nв том числе сахар 6 г")
        assertEquals(8.5, result.carbohydrateGramsPer100g)
        assertEquals(NutritionBasis.PER_100_GRAMS, result.basis)
        assertEquals("Йогурт", result.productName)
        assertEquals(CarbohydrateDefinition.UNSPECIFIED, result.carbohydrateDefinition)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `English total carbohydrate does not subtract fibre or copy daily value percent`() {
        val result = NutritionLabelParser.parse("Nutrition per 100 g\nTotal Carbohydrate 32 g 11%\nDietary Fiber 6 g\nSugars 2 g")
        assertEquals(32.0, result.carbohydrateGramsPer100g)
        assertEquals(CarbohydrateDefinition.TOTAL, result.carbohydrateDefinition)
    }

    @Test
    fun `available carbohydrate needs an explicit definition`() {
        val result = NutritionLabelParser.parse("Per 100g\nAvailable carbohydrate 12.7g")
        assertEquals(12.7, result.carbohydrateGramsPer100g)
        assertEquals(CarbohydrateDefinition.AVAILABLE, result.carbohydrateDefinition)
    }

    @Test
    fun `explicit zero is valid but absent carbohydrate is never zero`() {
        assertEquals(0.0, NutritionLabelParser.parse("На 100 г\nУглеводы 0 г").carbohydrateGramsPer100g)
        assertNull(NutritionLabelParser.parse("На 100 г\nБелки 10 г\nСахар 0 г").carbohydrateGramsPer100g)
        assertEquals(NutritionParseIssue.CARBOHYDRATE_NOT_FOUND, NutritionLabelParser.parse("На 100 г\nБелки 10 г").issue)
    }

    @Test
    fun `millilitres and serving amounts are not treated as per100 grams`() {
        val liquid = NutritionLabelParser.parse("На 100 мл\nУглеводы 4,7 г")
        assertNull(liquid.carbohydrateGramsPer100g)
        assertEquals(NutritionBasis.PER_100_MILLILITERS, liquid.basis)
        assertEquals(listOf(4.7), liquid.candidates)
        val serving = NutritionLabelParser.parse("Serving size 45 g\nTotal Carbohydrate 32 g")
        assertNull(serving.carbohydrateGramsPer100g)
        assertEquals(NutritionBasis.PER_SERVING, serving.basis)
    }

    @Test
    fun `package weight does not provide a missing nutrition denominator`() {
        val result = NutritionLabelParser.parse("Net weight 100 g\nCarbohydrate 20 g")
        assertNull(result.carbohydrateGramsPer100g)
        assertEquals(NutritionParseIssue.MISSING_BASIS, result.issue)
    }

    @Test
    fun `dual nutrition columns and conflicting duplicate amounts need manual resolution`() {
        val columns = NutritionLabelParser.parse("Per 100g / per serving\nCarbohydrate 40g 12g")
        assertNull(columns.carbohydrateGramsPer100g)
        assertEquals(NutritionParseIssue.AMBIGUOUS_VALUE, columns.issue)
        val repeated = NutritionLabelParser.parse("Per 100g\nCarbohydrate 40g\nCarbohydrate 42g")
        assertNull(repeated.carbohydrateGramsPer100g)
    }

    @Test
    fun `a local denominator does not become another numeric carbohydrate candidate`() {
        val result = NutritionLabelParser.parse("Carbohydrate 12.3 g per 100 g")
        assertEquals(12.3, result.carbohydrateGramsPer100g)
        assertEquals(listOf(12.3), result.candidates)
    }

    @Test
    fun `a split OCR row is supported without consuming protein as carbohydrate`() {
        assertEquals(8.5, NutritionLabelParser.parse("На 100 г\nУглеводы\n8,5 г\nБелки 4 г").carbohydrateGramsPer100g)
        assertNull(NutritionLabelParser.parse("На 100 г\nУглеводы\nБелки 4 г").carbohydrateGramsPer100g)
    }

    @Test
    fun `garbled digits negative or physically invalid amounts remain unresolved`() {
        for (text in listOf("Углеводы O.5 г", "Углеводы -2 г", "Углеводы 120 г", "Углеводы 5О г")) {
            assertNull(NutritionLabelParser.parse("На 100 г\n$text").carbohydrateGramsPer100g, text)
        }
    }

    @Test
    fun `inequalities and approximate amounts are not silently turned into exact zero`() {
        for (text in listOf("Carbohydrate <0.5g", "Углеводы ≈ 2 г", "Углеводы около 3 г")) {
            val result = NutritionLabelParser.parse("Per 100g\n$text")
            assertNull(result.carbohydrateGramsPer100g, text)
            assertEquals(NutritionParseIssue.APPROXIMATE_VALUE, result.issue, text)
        }
    }

    @Test
    fun `net carbohydrate is not equated with verified available carbohydrate`() {
        val result = NutritionLabelParser.parse("Per 100g\nNet carbs 8 g")
        assertNull(result.carbohydrateGramsPer100g)
        assertEquals(NutritionParseIssue.UNSUPPORTED_CARBOHYDRATE_DEFINITION, result.issue)
    }

    @Test
    fun `Russian product suffix is not mistaken for a per100 preposition`() {
        val result = NutritionLabelParser.parse("Масса банана100г\nУглеводы 20 г")
        assertNull(result.carbohydrateGramsPer100g)
        assertEquals(NutritionParseIssue.MISSING_BASIS, result.issue)
    }

    @Test
    fun `full gram units are recognised and oversized text cannot hide conflicts`() {
        assertEquals(12.0, NutritionLabelParser.parse("Per 100 grams\nCarbohydrate 12 grams").carbohydrateGramsPer100g)
        val result = NutritionLabelParser.parse("Per 100g\nCarbohydrate 12g\n" + "x".repeat(NutritionLabelParser.MAX_TEXT_LENGTH))
        assertNull(result.carbohydrateGramsPer100g)
        assertEquals(NutritionParseIssue.TEXT_TOO_LONG, result.issue)
    }

    @Test
    fun `fragmented digits or separated negative sign are not dropped from an OCR amount`() {
        for (amount in listOf("6 000g", "1, 5g", "- 2g")) {
            assertNull(NutritionLabelParser.parse("Per 100g\nCarbohydrate $amount").carbohydrateGramsPer100g, amount)
        }
    }
}
