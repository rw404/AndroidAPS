package app.aaps.ui.food

import java.io.Reader

/** Parser for the pinned USDA export. Missing and non-physical values remain unknown. */
object FoodCatalogParser {

    private val expectedHeader = listOf("id", "name", "dataType", "carbs", "fiber", "fat", "protein", "energyKcal", "preparation", "sourceUrl")
    private val sourceIdPattern = Regex("[0-9]+")

    fun read(reader: Reader): List<FoodProduct> = reader.buffered().use { input ->
        val header = input.readLine()?.removePrefix("\uFEFF")?.split('\t')
        require(header == expectedHeader) { "Unrecognized food catalog schema" }
        input.lineSequence().filter { it.isNotBlank() }.mapIndexed { index, line -> parseRow(line, index + 2) }.toList()
    }

    private fun parseRow(line: String, lineNumber: Int): FoodProduct {
        val fields = line.split('\t')
        require(fields.size == expectedHeader.size) { "Invalid food catalog row $lineNumber" }
        val sourceId = fields[0]
        require(sourceId.matches(sourceIdPattern) && fields[1].isNotBlank()) { "Missing food identity at row $lineNumber" }
        val source = when (fields[2]) {
            "foundation_food" -> FoodSource.USDA_FOUNDATION
            "sr_legacy_food" -> FoodSource.USDA_SR_LEGACY
            "survey_fndds_food" -> FoodSource.USDA_FNDDS
            else -> error("Unknown USDA data type at row $lineNumber")
        }
        val sourceUrl = "https://fdc.nal.usda.gov/food-details/$sourceId/nutrients"
        require(fields[9] == sourceUrl) { "Invalid food source URL at row $lineNumber" }
        val preparation = when (fields[8]) {
            "RAW" -> FoodPreparation.RAW
            "COOKED" -> FoodPreparation.COOKED
            "UNKNOWN" -> FoodPreparation.UNKNOWN
            else -> error("Unknown preparation at row $lineNumber")
        }
        val nutrients = (3..7).map { field -> fields[field].toDoubleOrNull()?.takeIf { value ->
            value.isFinite() && value >= 0 && (field == 7 || value <= 100)
        } }
        val warnings = buildList {
            if (nutrients[0] == null) add("Carbohydrate amount is unavailable; it is not zero.")
            if ((3..7).any { fields[it].isNotBlank() && nutrients[it - 3] == null }) add("An invalid nutrient value was excluded.")
        }
        return FoodProduct(
            id = "usda:$sourceId",
            name = fields[1],
            source = source,
            sourceUrl = sourceUrl,
            dataType = fields[2],
            basis = NutritionBasis.PER_100_GRAMS,
            carbohydrateDefinition = CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER,
            preparation = preparation,
            carbohydrateGrams = nutrients[0],
            fiberGrams = nutrients[1],
            fatGrams = nutrients[2],
            proteinGrams = nutrients[3],
            energyKcal = nutrients[4],
            warnings = warnings
        )
    }
}
