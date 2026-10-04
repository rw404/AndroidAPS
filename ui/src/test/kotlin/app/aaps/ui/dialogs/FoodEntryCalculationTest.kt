package app.aaps.ui.dialogs

import app.aaps.ui.food.CarbohydrateDefinition
import app.aaps.ui.food.FoodPreparation
import app.aaps.ui.food.FoodProduct
import app.aaps.ui.food.FoodSource
import app.aaps.ui.food.NutritionBasis
import app.aaps.ui.food.PortionUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FoodEntryCalculationTest {

    private fun food(carbs: Double? = 10.0, basis: NutritionBasis = NutritionBasis.PER_100_GRAMS,
                     definition: CarbohydrateDefinition = CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER) =
        FoodProduct("test", "Food", FoodSource.USDA_FOUNDATION, basis = basis,
                    carbohydrateDefinition = definition, preparation = FoodPreparation.UNKNOWN,
                    carbohydrateGrams = carbs, fiberGrams = 8.0)

    private fun portion(food: FoodProduct = food(), amount: Double = 100.0, unit: PortionUnit = PortionUnit.GRAMS) =
        CheckedFoodPortion(food, amount, unit)

    private fun input(portions: List<CheckedFoodPortion>, pending: Int = 0, selected: Boolean = false, limit: Int = 200) =
        FoodEntryCalculation.wizardInput(portions, pending, selected, limit)

    @Test fun `meal is summed before it is rounded once`() {
        val result = input(listOf(portion(food(0.26)), portion(food(0.26))))!!
        assertEquals(0.52, result.carbohydrateGrams, 1e-12)
        assertEquals(1, result.roundedCarbs)
    }

    @Test fun `known zero is valid while unknown is never zero`() {
        assertEquals(0, input(listOf(portion(food(0.0))))!!.roundedCarbs)
        assertNull(input(listOf(portion(food(null)))))
        assertNull(input(emptyList()))
    }

    @Test fun `unresolved parts and a selected unadded food prevent partial handoff`() {
        val checked = listOf(portion())
        assertNull(input(checked, pending = 1))
        assertNull(input(checked, pending = -1))
        assertNull(input(checked, selected = true))
        assertEquals(10, input(checked)!!.roundedCarbs)
    }

    @Test fun `unknown carbohydrate convention prevents handoff`() {
        assertNull(input(listOf(portion(food(definition = CarbohydrateDefinition.UNKNOWN)))))
    }

    @Test fun `fiber is not subtracted or added for either known source convention`() {
        val us = portion(food())
        val eu = portion(food(definition = CarbohydrateDefinition.EU_AVAILABLE))
        assertEquals(10.0, input(listOf(us))!!.carbohydrateGrams, 1e-12)
        assertEquals(10.0, input(listOf(eu))!!.carbohydrateGrams, 1e-12)
        assertNull(input(listOf(us, eu)))
    }

    @Test fun `milliliter sources require milliliters and retain portion scaling`() {
        val liquid = food(6.0, NutritionBasis.PER_100_MILLILITERS)
        assertNull(input(listOf(portion(liquid))))
        assertEquals(15.0, input(listOf(portion(liquid, 250.0, PortionUnit.MILLILITERS)))!!.carbohydrateGrams, 1e-12)
        assertNull(input(listOf(portion(food(), unit = PortionUnit.MILLILITERS))))
    }

    @Test fun `unrounded limit violation cannot be hidden by rounding`() {
        val portions = listOf(portion(food(2.49)))
        assertNull(input(portions, limit = 2))
        assertEquals(2, input(portions, limit = 3)!!.roundedCarbs)
        assertNull(input(portions, limit = -1))
    }

    @Test fun `nonfinite negative and oversized inputs are rejected`() {
        for (mass in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 10000.01))
            assertNull(input(listOf(portion(amount = mass))))
        for (carbs in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 100.01))
            assertNull(input(listOf(portion(food(carbs)))))
    }

    @Test fun `unknown and per serving denominators are not silently treated as 100 grams`() {
        assertNull(input(listOf(portion(food(basis = NutritionBasis.UNKNOWN)))))
        assertNull(input(listOf(portion(food(basis = NutritionBasis.PER_SERVING)))))
    }

    @Test fun `too many parts are rejected and unspecified preparation stays explicitly usable`() {
        assertNull(input(List(FoodEntryCalculation.MAX_COMPONENTS + 1) { portion(food(0.0)) }))
        assertEquals(10, input(listOf(portion()))!!.roundedCarbs)
    }
}
