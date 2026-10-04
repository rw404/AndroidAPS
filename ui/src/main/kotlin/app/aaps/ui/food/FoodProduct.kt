package app.aaps.ui.food

/** The basis is part of the value; a milliliter is never silently treated as a gram. */
enum class NutritionBasis { PER_100_GRAMS, PER_100_MILLILITERS, PER_SERVING, UNKNOWN }

enum class CarbohydrateDefinition { US_TOTAL_INCLUDES_FIBER, EU_AVAILABLE, UNKNOWN }

enum class FoodPreparation { RAW, COOKED, AS_SOLD, AS_PREPARED, UNKNOWN }

enum class PortionUnit { GRAMS, MILLILITERS }

enum class FoodSource {
    USDA_FOUNDATION, USDA_SR_LEGACY, USDA_FNDDS, OPEN_FOOD_FACTS, USER_LABEL
}

/** Nutrient amounts refer to [basis]. Source values and original names are preserved. */
data class FoodProduct(
    val id: String,
    val name: String,
    val source: FoodSource,
    val sourceUrl: String? = null,
    val dataType: String? = null,
    val basis: NutritionBasis = NutritionBasis.UNKNOWN,
    val carbohydrateDefinition: CarbohydrateDefinition = CarbohydrateDefinition.UNKNOWN,
    val preparation: FoodPreparation = FoodPreparation.UNKNOWN,
    val carbohydrateGrams: Double? = null,
    val fiberGrams: Double? = null,
    val fatGrams: Double? = null,
    val proteinGrams: Double? = null,
    val energyKcal: Double? = null,
    val barcode: String? = null,
    val warnings: List<String> = emptyList()
) {

    val carbsPer100g: Double?
        get() = usableCarbohydrate().takeIf { basis == NutritionBasis.PER_100_GRAMS }

    val carbsPer100: Double?
        get() = usableCarbohydrate().takeIf { basis == NutritionBasis.PER_100_GRAMS || basis == NutritionBasis.PER_100_MILLILITERS }

    val sourceLabel: String
        get() = when (source) {
            FoodSource.USDA_FOUNDATION -> "USDA Foundation Foods"
            FoodSource.USDA_SR_LEGACY -> "USDA SR Legacy"
            FoodSource.USDA_FNDDS -> "USDA FNDDS"
            FoodSource.OPEN_FOOD_FACTS -> "Open Food Facts (ODbL)"
            FoodSource.USER_LABEL -> "User-confirmed nutrition label"
        }

    /** Only scales a known, compatible nutrient basis. It does not choose a counting convention. */
    fun carbohydrateFor(amount: Double, unit: PortionUnit): Double? {
        if (!amount.isFinite() || amount <= 0) return null
        val compatible = when (basis) {
            NutritionBasis.PER_100_GRAMS -> unit == PortionUnit.GRAMS
            NutritionBasis.PER_100_MILLILITERS -> unit == PortionUnit.MILLILITERS
            NutritionBasis.PER_SERVING, NutritionBasis.UNKNOWN -> false
        }
        if (!compatible) return null
        val carbohydrate = usableCarbohydrate() ?: return null
        return (carbohydrate * amount / 100).takeIf { it.isFinite() }
    }

    private fun usableCarbohydrate(): Double? = carbohydrateGrams?.takeIf {
        it.isFinite() && it >= 0 && (basis != NutritionBasis.PER_100_GRAMS || it <= 100)
    }
}

sealed interface BarcodeLookupResult {
    data class Found(val product: FoodProduct) : BarcodeLookupResult
    data object NotFound : BarcodeLookupResult
    data class Failure(val reason: String) : BarcodeLookupResult
}
