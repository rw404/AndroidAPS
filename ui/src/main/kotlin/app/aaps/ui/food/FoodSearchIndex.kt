package app.aaps.ui.food

import java.text.Normalizer
import java.util.Locale

/** Russian aliases expand queries only. Original USDA product names are never replaced. */
class FoodSearchIndex(products: List<FoodProduct>) {

    private data class Entry(val product: FoodProduct, val text: String, val words: Set<String>)
    private val entries = products.map { product ->
        // Ingredient exclusions in USDA descriptions must not count as positive matches.
        val text = normalize(product.name.replace(ingredientExclusions, " "))
        Entry(product, text, text.split(' ').filter { it.isNotBlank() }.toSet())
    }
    val size: Int get() = entries.size

    fun search(query: String, limit: Int = 60): List<FoodProduct> {
        val terms = normalize(query).split(' ').filter { it.isNotBlank() && it !in grammaticalConnectors && !it.all(Char::isDigit) }
        if (terms.isEmpty()) return emptyList()
        val groups = terms.map { term -> aliases[term] ?: listOf(term) }
        return entries.asSequence().mapNotNull { entry ->
            val scores = groups.map { alternatives ->
                alternatives.maxOf { alternative ->
                    when {
                        alternative in entry.words -> 4
                        alternative.contains(' ') && entry.text.contains(alternative) -> 3
                        alternative.length >= 3 && entry.words.any { it.startsWith(alternative) } -> 2
                        else -> 0
                    }
                }
            }
            // Prefer the food named by the leading term over another food that contains it:
            // "egg" should rank Egg, whole above Bread, egg, without hiding composite dishes.
            val leadingFoodBonus = if (groups.first().any { alternative -> entry.text == alternative || entry.text.startsWith("$alternative ") }) 2 else 0
            if (scores.any { it == 0 }) null else entry to (scores.sum() + leadingFoodBonus)
        }.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.text.length }.thenBy { it.first.product.id })
            .take(limit.coerceIn(1, 200)).map { it.first.product }.toList()
    }

    companion object {

        private val nonWords = Regex("[^\\p{L}\\p{N}]+")
        private val combiningMarks = Regex("\\p{M}+")
        private val ingredientExclusions = Regex("\\b(?:without|no|not|excluding)\\s+[^,;()]+", RegexOption.IGNORE_CASE)
        // "with" is grammatical; both ingredient terms still have to match a real source name.
        private val grammaticalConnectors = setOf("с", "со", "with", "and", "и")

        private fun normalize(value: String): String =
            Normalizer.normalize(value.lowercase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFKD)
                .replace(combiningMarks, "").replace(nonWords, " ").trim()

        // Ingredient translations, not claims that a particular regional recipe has identical nutrition.
        private val aliases: Map<String, List<String>> = buildMap {
            fun add(russian: List<String>, vararg english: String) = russian.forEach { put(normalize(it), english.map(::normalize)) }
            add(listOf("рис", "риса", "рисом", "рисовый", "рисовая", "рисовой"), "rice")
            add(listOf("гречка", "гречки", "гречневая", "гречневой"), "buckwheat")
            add(listOf("овсянка", "овсяная", "овсяной", "овсяные", "овес", "овса"), "oats", "oatmeal")
            add(listOf("каша", "каши", "кашу"), "porridge", "cereal", "groats", "oatmeal")
            add(listOf("макароны", "макарон", "паста"), "pasta", "macaroni", "spaghetti")
            add(listOf("спагетти"), "spaghetti")
            add(listOf("хлеб", "хлеба"), "bread")
            add(listOf("картофель", "картофеля", "картошка", "картошки"), "potato", "potatoes")
            add(listOf("пюре"), "mashed")
            add(listOf("суп", "супа", "супы"), "soup")
            add(listOf("борщ", "борща"), "borscht", "borsch")
            add(listOf("плов", "плова"), "pilaf")
            add(listOf("салат", "салата"), "salad")
            add(listOf("блины", "блин", "блинов", "блинчики"), "pancake", "pancakes", "crepe", "crepes")
            add(listOf("яйцо", "яйца", "яиц"), "egg", "eggs")
            add(listOf("омлет", "омлета"), "omelet", "omelette")
            add(listOf("курица", "курицы", "курицей", "куриная", "куриной", "куриное"), "chicken")
            add(listOf("грудка", "грудки"), "breast")
            add(listOf("индейка", "индейки"), "turkey")
            add(listOf("говядина", "говядины"), "beef")
            add(listOf("свинина", "свинины"), "pork")
            add(listOf("рыба", "рыбы", "рыбный"), "fish")
            add(listOf("лосось", "лосося", "семга"), "salmon")
            add(listOf("тунец", "тунца"), "tuna")
            add(listOf("молоко", "молока", "молочный"), "milk")
            add(listOf("йогурт", "йогурта"), "yogurt", "yoghurt")
            add(listOf("сыр", "сыра"), "cheese")
            add(listOf("творог", "творога"), "cottage cheese")
            add(listOf("сливки", "сливок"), "cream")
            add(listOf("сметана", "сметаны"), "sour cream")
            add(listOf("масло", "масла"), "oil", "butter")
            add(listOf("оливковое", "оливкового"), "olive")
            add(listOf("яблоко", "яблока", "яблоки", "яблок"), "apple", "apples")
            add(listOf("банан", "банана", "бананы"), "banana", "bananas")
            add(listOf("апельсин", "апельсина"), "orange", "oranges")
            add(listOf("виноград", "винограда"), "grape", "grapes")
            add(listOf("ягоды", "ягод"), "berries")
            add(listOf("клубника", "клубники"), "strawberry", "strawberries")
            add(listOf("морковь", "моркови"), "carrot", "carrots")
            add(listOf("помидор", "помидоры", "томаты"), "tomato", "tomatoes")
            add(listOf("огурец", "огурцы"), "cucumber", "cucumbers")
            add(listOf("капуста", "капусты"), "cabbage")
            add(listOf("брокколи"), "broccoli")
            add(listOf("лук", "лука"), "onion", "onions")
            add(listOf("чеснок", "чеснока"), "garlic")
            add(listOf("грибы", "грибов"), "mushroom", "mushrooms")
            add(listOf("фасоль", "фасоли"), "beans")
            add(listOf("чечевица", "чечевицы"), "lentils")
            add(listOf("горох", "гороха"), "peas")
            add(listOf("орехи", "орехов"), "nuts")
            add(listOf("миндаль", "миндаля"), "almonds")
            add(listOf("арахис", "арахиса"), "peanuts")
            add(listOf("шоколад", "шоколада"), "chocolate")
            add(listOf("сахар", "сахара"), "sugar")
            add(listOf("мед", "меда"), "honey")
            add(listOf("сок", "сока"), "juice")
            add(listOf("чай", "чая"), "tea")
            add(listOf("кофе"), "coffee")
            add(listOf("вода", "воды"), "water")
            add(listOf("соус", "соуса"), "sauce")
            add(listOf("майонез", "майонеза"), "mayonnaise")
            add(listOf("сухой", "сухая", "сухие"), "dry", "dried")
            add(listOf("сырой", "сырая", "сырые"), "raw", "uncooked")
            add(listOf("вареный", "вареная", "вареные", "вареной", "готовый", "готовая", "готовые", "приготовленный"), "cooked", "boiled", "steamed")
            add(listOf("жареный", "жареная", "жареные", "жареной"), "fried")
            add(listOf("запеченный", "запеченная", "запеченные"), "baked", "roasted")
            add(listOf("белый", "белая", "белого"), "white")
            add(listOf("бурый", "коричневый", "коричневого"), "brown")
        }
    }
}
