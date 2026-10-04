package app.aaps.ui.food

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenFoodFactsProductParserTest {

    @Test
    fun `actual barcode response preserves values but does not invent label convention or basis`() {
        val result = OpenFoodFactsProductParser.parse(fixtureText(), "3017620422003") as BarcodeLookupResult.Found
        val food = result.product
        assertEquals("off:3017620422003", food.id)
        assertEquals(FoodSource.OPEN_FOOD_FACTS, food.source)
        assertEquals("Nutella", food.name)
        assertEquals(57.5, food.carbohydrateGrams)
        assertEquals(0.0, food.fiberGrams)
        assertEquals(NutritionBasis.UNKNOWN, food.basis)
        assertEquals(CarbohydrateDefinition.UNKNOWN, food.carbohydrateDefinition)
        assertNull(food.carbohydrateFor(100.0, PortionUnit.GRAMS))
        assertTrue(food.sourceLabel.contains("Open Food Facts"))
        assertEquals("https://world.openfoodfacts.org/product/3017620422003", food.sourceUrl)
    }

    @Test
    fun `missing carbohydrate is never replaced by zero`() {
        val json = JsonParser.parseString(fixtureText()).asJsonObject
        json.getAsJsonObject("product").getAsJsonObject("nutriments").remove("carbohydrates_100g")
        val product = (OpenFoodFactsProductParser.parse(json.toString(), "3017620422003") as BarcodeLookupResult.Found).product
        assertNull(product.carbohydrateGrams)
        assertTrue(product.warnings.any { it.contains("not zero") })
    }

    @Test
    fun `invalid responses fail without producing guessed nutrient values`() {
        assertEquals(BarcodeLookupResult.NotFound, OpenFoodFactsProductParser.parse("{\"status\":0}", "3017620422003"))
        assertTrue(OpenFoodFactsProductParser.parse("not-json", "3017620422003") is BarcodeLookupResult.Failure)
        assertTrue(OpenFoodFactsProductParser.parse("{\"product\":[]}", "3017620422003") is BarcodeLookupResult.Failure)
    }

    @Test
    fun `a different or missing barcode can never prefill another product`() {
        for (code in listOf("12345678", "not-a-barcode", "", null)) {
            val json = JsonParser.parseString(fixtureText()).asJsonObject
            val product = json.getAsJsonObject("product")
            if (code == null) product.remove("code") else product.addProperty("code", code)
            assertTrue(OpenFoodFactsProductParser.parse(json.toString(), "3017620422003") is BarcodeLookupResult.Failure)
        }
    }

    private fun fixtureText() = requireNotNull(javaClass.getResourceAsStream("/food/openfoodfacts_3017620422003.json")).bufferedReader().use { it.readText() }
}
