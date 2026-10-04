package app.aaps.ui.food

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FoodProductTest {

    @Test
    fun `grams and milliliters are not interchangeable`() {
        val food = product(10.0, NutritionBasis.PER_100_GRAMS)
        assertEquals(15.0, food.carbohydrateFor(150.0, PortionUnit.GRAMS))
        assertNull(food.carbohydrateFor(150.0, PortionUnit.MILLILITERS))
        val drink = food.copy(basis = NutritionBasis.PER_100_MILLILITERS)
        assertNull(drink.carbsPer100g)
        assertNull(drink.carbohydrateFor(150.0, PortionUnit.GRAMS))
        assertEquals(15.0, drink.carbohydrateFor(150.0, PortionUnit.MILLILITERS))
    }

    @Test
    fun `known zero is distinct from missing or invalid carbohydrate`() {
        assertEquals(0.0, product(0.0).carbohydrateFor(100.0, PortionUnit.GRAMS))
        for (value in listOf(null, -1.0, 100.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(product(value).carbsPer100g)
            assertNull(product(value).carbohydrateFor(100.0, PortionUnit.GRAMS))
        }
        // A liquid can exceed 100 g of carbohydrate per 100 ml; no density is assumed.
        assertEquals(120.0, product(120.0, NutritionBasis.PER_100_MILLILITERS).carbohydrateFor(100.0, PortionUnit.MILLILITERS))
    }

    @Test
    fun `unknown basis serving size and invalid portions cannot produce a total`() {
        for (basis in listOf(NutritionBasis.UNKNOWN, NutritionBasis.PER_SERVING))
            assertNull(product(10.0, basis).carbohydrateFor(100.0, PortionUnit.GRAMS))
        for (amount in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(product(10.0).carbohydrateFor(amount, PortionUnit.GRAMS))
    }

    @Test
    fun `US total carbohydrate is never silently reduced by fiber`() {
        val food = product(30.0).copy(fiberGrams = 10.0)
        assertEquals(30.0, food.carbohydrateFor(100.0, PortionUnit.GRAMS))
        assertEquals(CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER, food.carbohydrateDefinition)
    }

    private fun product(carbs: Double?, basis: NutritionBasis = NutritionBasis.PER_100_GRAMS) = FoodProduct(
        id = "validation-fixture",
        name = "Synthetic input for unit validation only",
        source = FoodSource.USER_LABEL,
        basis = basis,
        carbohydrateDefinition = CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER,
        carbohydrateGrams = carbs
    )
}
