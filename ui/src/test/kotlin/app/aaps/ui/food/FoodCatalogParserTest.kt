package app.aaps.ui.food

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.StringReader

class FoodCatalogParserTest {

    @Test
    fun `real USDA fixtures retain nutrient source and preparation metadata`() {
        val foods = fixtures()
        val cooked = foods.single { it.id == "usda:168878" }
        assertEquals("Rice, white, long-grain, regular, enriched, cooked", cooked.name)
        assertEquals(FoodSource.USDA_SR_LEGACY, cooked.source)
        assertEquals(FoodPreparation.COOKED, cooked.preparation)
        assertEquals(NutritionBasis.PER_100_GRAMS, cooked.basis)
        assertEquals(CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER, cooked.carbohydrateDefinition)
        assertEquals(28.17, cooked.carbsPer100g)
        assertEquals(0.4, cooked.fiberGrams)
        assertEquals("https://fdc.nal.usda.gov/food-details/168878/nutrients", cooked.sourceUrl)
        val borscht = foods.single { it.id == "usda:2710105" }
        assertEquals(FoodSource.USDA_FNDDS, borscht.source)
        assertEquals(3.9, borscht.carbsPer100g)
        assertEquals(FoodPreparation.UNKNOWN, borscht.preparation)
    }

    @Test
    fun `raw and cooked rice remain different foods and values`() {
        val foods = fixtures()
        val raw = foods.single { it.id == "usda:2512381" }
        val cooked = foods.single { it.id == "usda:168878" }
        assertEquals(FoodPreparation.RAW, raw.preparation)
        assertEquals(80.31315, raw.carbsPer100g)
        assertEquals(369.637321, raw.energyKcal)
        assertNotEquals(raw.carbohydrateFor(150.0, PortionUnit.GRAMS), cooked.carbohydrateFor(150.0, PortionUnit.GRAMS))
        assertEquals(42.255, cooked.carbohydrateFor(150.0, PortionUnit.GRAMS)!!, 1e-9)
    }

    @Test
    fun `missing nutrients on an actual Foundation food stay unknown`() {
        val salt = fixtures().single { it.id == "usda:321505" }
        assertNull(salt.carbohydrateGrams)
        assertNull(salt.fiberGrams)
        assertNull(salt.carbohydrateFor(100.0, PortionUnit.GRAMS))
    }

    @Test
    fun `invalid or physically impossible gram nutrient is unknown rather than clamped`() {
        val original = fixtureText().lineSequence().first { it.startsWith("168878\t") }.split('\t')
        val header = fixtureText().lineSequence().first()
        for (field in 3..6) {
            for (invalid in listOf("NaN", "Infinity", "-0.5", "100.01", "not a number")) {
                val fields = original.toMutableList().also { it[field] = invalid }
                val product = FoodCatalogParser.read(StringReader(header + "\n" + fields.joinToString("\t") + "\n")).single()
                val nutrient = listOf(product.carbohydrateGrams, product.fiberGrams, product.fatGrams, product.proteinGrams)[field - 3]
                assertNull(nutrient, "field=$field value=$invalid")
            }
        }
    }

    @Test
    fun `source URL identity mismatch and unknown schema are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FoodCatalogParser.read(StringReader(fixtureText().replace("food-details/168878/nutrients", "food-details/123/nutrients")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FoodCatalogParser.read(StringReader(fixtureText().replaceFirst("carbs\t", "netCarbs\t")))
        }
    }

    private fun fixtures() = FoodCatalogParser.read(StringReader(fixtureText()))
    private fun fixtureText() = requireNotNull(javaClass.getResourceAsStream("/food/usda_fixtures.tsv")).bufferedReader().use { it.readText() }
}
