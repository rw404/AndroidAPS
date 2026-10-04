package app.aaps.ui.food.recognition

import java.util.Locale

enum class NutritionBasis { PER_100_GRAMS, PER_100_MILLILITERS, PER_SERVING, UNKNOWN }

enum class CarbohydrateDefinition { TOTAL, AVAILABLE, UNSPECIFIED }

enum class NutritionParseIssue {
    CARBOHYDRATE_NOT_FOUND,
    VALUE_NOT_READABLE,
    INVALID_VALUE,
    APPROXIMATE_VALUE,
    AMBIGUOUS_VALUE,
    MISSING_BASIS,
    AMBIGUOUS_BASIS,
    UNSUPPORTED_BASIS,
    UNSUPPORTED_CARBOHYDRATE_DEFINITION,
    TEXT_TOO_LONG
}

/** OCR proposals, never a verified nutrition record. Missing values remain null. */
data class NutritionLabelResult(
    val carbohydrateGramsPer100g: Double?,
    val basis: NutritionBasis,
    val productName: String?,
    val candidates: List<Double> = emptyList(),
    val issue: NutritionParseIssue? = null,
    val carbohydrateDefinition: CarbohydrateDefinition = CarbohydrateDefinition.UNSPECIFIED,
    val requiresReview: Boolean = true
)

/**
 * Reads explicitly labelled RU/EN carbohydrate amounts in grams. It does not infer
 * carbohydrates from sugars, convert serving sizes/densities, repair OCR digits,
 * or interpret a package's net weight as its nutrition denominator.
 */
object NutritionLabelParser {

    private val carbohydrate = Regex("(?:\\b(?:total\\s+|available\\s+|digestible\\s+|net\\s+)?carbohydrates?\\b|\\bcarbs\\b|(?<![\\p{L}])углевод(?:ы|ов)(?![\\p{L}]))", RegexOption.IGNORE_CASE)
    private val nutrient = Regex("(?:protein|fat|sugar|fibre|fiber|salt|sodium|energy|белк|жир|сахар|клетчат|соль|энерг)", RegexOption.IGNORE_CASE)
    private val gramsBasis = Regex("(?:\\bper\\s*|(?<![\\p{L}])(?:на|в)\\s*|/)100\\s*(?:g(?:rams?)?|г(?:рамм(?:а|ов)?)?)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val millilitersBasis = Regex("(?:\\bper\\s*|(?<![\\p{L}])(?:на|в)\\s*|/)100\\s*(?:ml|мл)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val servingBasis = Regex("(?:\\bper\\s+serving\\b|\\bserving\\s+size\\b|на\\s+порци|размер\\s+порци)", RegexOption.IGNORE_CASE)
    private val gramAmount = Regex("(?<![\\p{L}\\d.,])([+-]?\\d+(?:[.,]\\d+)?)\\s*(?:g(?:rams?)?|г(?:рамм(?:а|ов)?)?)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val approximate = Regex("[<>≤≥≈~]|\\bapprox|примерн|около", RegexOption.IGNORE_CASE)
    private val explicitName = Regex("^(?:product(?:\\s+name)?|name|название|наименование(?:\\s+продукта)?|продукт)\\s*:\\s*(.{2,100})$", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): NutritionLabelResult {
        if (rawText.length > MAX_TEXT_LENGTH) return NutritionLabelResult(null, NutritionBasis.UNKNOWN, null, issue = NutritionParseIssue.TEXT_TOO_LONG)
        val lines = rawText.replace('\u00a0', ' ').replace('−', '-').lines().map(String::trim).filter(String::isNotEmpty)
        val productName = lines.firstNotNullOfOrNull { explicitName.matchEntire(it)?.groupValues?.get(1)?.trim() }
        val rows = lines.indices.filter { carbohydrate.containsMatchIn(lines[it]) }.map { index ->
            val line = lines[index]
            val match = carbohydrate.find(line)!!
            var tail = line.substring(match.range.last + 1)
            if (!gramAmount.containsMatchIn(tail)) {
                // A label/value may be split by OCR. Do not cross another nutrient row.
                val following = lines.drop(index + 1).take(2).takeWhile {
                    !carbohydrate.containsMatchIn(it) && !nutrient.containsMatchIn(it) && !gramsBasis.containsMatchIn(it) && !millilitersBasis.containsMatchIn(it)
                }
                tail += " " + following.joinToString(" ")
            }
            val valueText = gramsBasis.replace(millilitersBasis.replace(tail, " "), " ")
            val amounts = gramAmount.findAll(valueText).toList()
            val fragmentedNumber = amounts.any { Regex("(?:\\d[.,]?|[+-])\\s+$").containsMatchIn(valueText.substring(0, it.range.first)) }
            val values = if (fragmentedNumber) emptyList() else amounts.mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            val normalized = line.lowercase(Locale.ROOT)
            val definition = when {
                Regex("\\btotal\\s+carbohydrate").containsMatchIn(normalized) -> CarbohydrateDefinition.TOTAL
                Regex("\\b(?:available|digestible)\\s+carbohydrate|усвояем").containsMatchIn(normalized) -> CarbohydrateDefinition.AVAILABLE
                else -> CarbohydrateDefinition.UNSPECIFIED
            }
            val unsupportedDefinition = Regex("\\bnet\\s+carb|чист(?:ые|ых)\\s+углевод").containsMatchIn(normalized)
            Row(values, bases(line), definition, approximate.containsMatchIn(valueText), unsupportedDefinition)
        }
        if (rows.isEmpty()) return NutritionLabelResult(null, NutritionBasis.UNKNOWN, productName, issue = NutritionParseIssue.CARBOHYDRATE_NOT_FOUND)

        val candidates = rows.flatMap(Row::values).distinct()
        val localBases = rows.flatMap { it.bases }.toSet()
        val detectedBases = localBases.ifEmpty { bases(lines.joinToString("\n")) }
        val basis = detectedBases.singleOrNull() ?: NutritionBasis.UNKNOWN
        val definitions = rows.map(Row::definition).distinct()
        val definition = definitions.singleOrNull() ?: CarbohydrateDefinition.UNSPECIFIED
        val value = candidates.singleOrNull()
        val issue = when {
            candidates.isEmpty() -> NutritionParseIssue.VALUE_NOT_READABLE
            candidates.size != 1 || rows.any { it.values.size > 1 } || definitions.size > 1 -> NutritionParseIssue.AMBIGUOUS_VALUE
            rows.any(Row::approximate) -> NutritionParseIssue.APPROXIMATE_VALUE
            value == null || !value.isFinite() || value < 0 || (basis == NutritionBasis.PER_100_GRAMS && value > 100) -> NutritionParseIssue.INVALID_VALUE
            detectedBases.size > 1 -> NutritionParseIssue.AMBIGUOUS_BASIS
            detectedBases.isEmpty() -> NutritionParseIssue.MISSING_BASIS
            basis != NutritionBasis.PER_100_GRAMS -> NutritionParseIssue.UNSUPPORTED_BASIS
            rows.any(Row::unsupportedDefinition) -> NutritionParseIssue.UNSUPPORTED_CARBOHYDRATE_DEFINITION
            else -> null
        }
        return NutritionLabelResult(if (issue == null) value else null, basis, productName, candidates, issue, definition)
    }

    private fun bases(text: String): Set<NutritionBasis> = buildSet {
        if (gramsBasis.containsMatchIn(text)) add(NutritionBasis.PER_100_GRAMS)
        if (millilitersBasis.containsMatchIn(text)) add(NutritionBasis.PER_100_MILLILITERS)
        if (servingBasis.containsMatchIn(text)) add(NutritionBasis.PER_SERVING)
    }

    private data class Row(
        val values: List<Double>,
        val bases: Set<NutritionBasis>,
        val definition: CarbohydrateDefinition,
        val approximate: Boolean,
        val unsupportedDefinition: Boolean
    )

    const val MAX_TEXT_LENGTH = 32_000
}
