package app.aaps.ui.food

data class MealDescription(val components: List<MealDescriptionComponent>)

data class MealDescriptionComponent(
    val rawText: String,
    val foodQuery: String,
    val amount: Double?,
    val unit: PortionUnit?,
    val needsPortionCheck: Boolean,
    val issue: MealDescriptionIssue?
)

enum class MealDescriptionIssue { MISSING_PORTION, AMBIGUOUS_PORTION, INVALID_PORTION, MISSING_FOOD }

/** Extracts explicit portions locally; identifying the exact food still requires catalog selection. */
object MealDescriptionParser {

    private val quantity = Regex(
        "(?<![\\p{L}\\p{N}.,+−-])([-+−]?\\d+(?:[.,]\\d+)?(?:[eE][-+]?\\d+)?)\\s*" +
            "(килограмм(?:а|ов)?|кг|kg|грамм(?:а|ов)?|grams?|г|g|миллилитр(?:а|ов)?|мл|ml|литр(?:а|ов)?|л|l)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )
    private val componentSeparator = Regex("[;\\n\\r]+|(?<!\\d),|,(?!\\d)")
    private val conjunction = Regex("\\s+(?:и|and|\\+)\\s+", RegexOption.IGNORE_CASE)
    private val approximate = Regex("(?:~|≈|(?<![\\p{L}\\p{N}])(?:около|примерно|приблизительно|about|approximately|around)(?![\\p{L}\\p{N}]))", RegexOption.IGNORE_CASE)
    private val multipliedPortion = Regex("\\d+\\s*[x×*]\\s*$", RegexOption.IGNORE_CASE)
    private val detachedSign = Regex("[+−-]\\s*$")

    fun parse(text: String): MealDescription = MealDescription(
        componentSeparator.split(text).filter { it.isNotBlank() }.flatMap(::splitExplicitComponents).map(::parseComponent)
    )

    private fun splitExplicitComponents(text: String): List<String> {
        // "Chicken and rice, 200 g" is one dish. Split "chicken 100 g and rice 150 g" only.
        val parts = conjunction.split(text)
        return if (parts.size > 1 && parts.all { quantity.containsMatchIn(it) }) parts else listOf(text)
    }

    private fun parseComponent(text: String): MealDescriptionComponent {
        val raw = text.trim()
        val matches = quantity.findAll(raw).toList()
        if (matches.size != 1) return MealDescriptionComponent(
            rawText = raw,
            foodQuery = raw,
            amount = null,
            unit = null,
            needsPortionCheck = true,
            issue = if (matches.isEmpty()) MealDescriptionIssue.MISSING_PORTION else MealDescriptionIssue.AMBIGUOUS_PORTION
        )
        val match = matches.single()
        val numeric = match.groupValues[1]
        val unitText = match.groupValues[2].lowercase()
        val unit = if (unitText in setOf("ml", "l", "мл", "л") || unitText.startsWith("миллилитр") || unitText.startsWith("литр"))
            PortionUnit.MILLILITERS else PortionUnit.GRAMS
        val multiplier = if (unitText in setOf("kg", "кг", "l", "л") || unitText.startsWith("килограмм") || unitText.startsWith("литр")) 1000.0 else 1.0
        val amount = numeric.replace(',', '.').replace('−', '-').toDoubleOrNull()?.times(multiplier)?.takeIf {
            // Scientific notation and signed portions are not accepted as measured meal descriptions.
            it.isFinite() && it > 0 && !numeric.contains('e', true) && !numeric.startsWith('+') && !numeric.startsWith('-') &&
                !multipliedPortion.containsMatchIn(raw.substring(0, match.range.first)) &&
                !detachedSign.containsMatchIn(raw.substring(0, match.range.first))
        }
        val foodQuery = approximate.replace(raw.removeRange(match.range), " ").trim(' ', '\t', ':', '-', '—').replace(Regex("\\s+"), " ")
        val issue = when {
            amount == null -> MealDescriptionIssue.INVALID_PORTION
            foodQuery.isBlank() -> MealDescriptionIssue.MISSING_FOOD
            else -> null
        }
        return MealDescriptionComponent(raw, foodQuery, amount, unit, approximate.containsMatchIn(raw) || issue != null, issue)
    }
}
