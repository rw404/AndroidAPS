package app.aaps.ui.dialogs

import app.aaps.ui.food.CarbohydrateDefinition
import app.aaps.ui.food.FoodProduct
import app.aaps.ui.food.PortionUnit
import kotlin.math.roundToInt

internal data class CheckedFoodPortion(val product: FoodProduct, val amount: Double, val unit: PortionUnit) {
    fun carbohydrate(): Double? = product.carbohydrateFor(amount, unit)
}

internal data class FoodWizardInput(val carbohydrateGrams: Double, val roundedCarbs: Int)

/** Keeps source definitions and unknowns intact; rounds only the complete checked meal. */
internal object FoodEntryCalculation {
    const val MAX_COMPONENTS = 30
    const val MAX_PORTION = 10000.0

    fun subtotal(portions: List<CheckedFoodPortion>): Double? {
        if (portions.isEmpty() || portions.size > MAX_COMPONENTS || hasMixedDefinitions(portions)) return null
        var total = 0.0
        for (portion in portions) {
            if (portion.product.carbohydrateDefinition == CarbohydrateDefinition.UNKNOWN ||
                !portion.amount.isFinite() || portion.amount <= 0 || portion.amount > MAX_PORTION) return null
            val carbs = portion.carbohydrate()?.takeIf { it.isFinite() && it >= 0 } ?: return null
            total += carbs
            if (!total.isFinite()) return null
        }
        return total
    }

    fun hasMixedDefinitions(portions: List<CheckedFoodPortion>): Boolean =
        portions.map { it.product.carbohydrateDefinition }.filter { it != CarbohydrateDefinition.UNKNOWN }.distinct().size > 1

    fun wizardInput(portions: List<CheckedFoodPortion>, unresolvedCount: Int, selectedFoodPresent: Boolean, maxCarbs: Int): FoodWizardInput? {
        if (unresolvedCount != 0 || selectedFoodPresent || maxCarbs < 0) return null
        val total = subtotal(portions) ?: return null
        // Check the unrounded source sum, so rounding cannot hide a native-limit violation.
        if (total > maxCarbs || total > Int.MAX_VALUE) return null
        return FoodWizardInput(total, total.roundToInt())
    }
}
