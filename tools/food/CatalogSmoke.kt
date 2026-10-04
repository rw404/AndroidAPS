package app.aaps.ui.food

import java.io.File

/** Source-selected JVM smoke check of every real bundled row, not an Android integration test. */
fun main(args: Array<String>) {
    val foods = FoodCatalogParser.read(File(args.single()).reader(Charsets.UTF_8))
    check(foods.size == 13_694) { "Unexpected pinned catalog size: ${foods.size}" }
    check(foods.map { it.id }.toSet().size == foods.size) { "Duplicate source identities" }
    check(foods.count { it.carbohydrateGrams == null } == 103) { "Missing nutrient count changed" }
    val sourceCounts = foods.groupingBy { it.source }.eachCount()
    check(sourceCounts == mapOf(FoodSource.USDA_FOUNDATION to 469, FoodSource.USDA_SR_LEGACY to 7_793, FoodSource.USDA_FNDDS to 5_432))
    check(foods.all { it.basis == NutritionBasis.PER_100_GRAMS && it.carbohydrateDefinition == CarbohydrateDefinition.US_TOTAL_INCLUDES_FIBER })
    val index = FoodSearchIndex(foods)
    for (query in listOf("рис сырой", "рис вареный", "гречка", "гречневая каша", "овсяная каша", "курица с рисом", "борщ", "плов", "картофель", "яйцо", "bread")) {
        val result = index.search(query)
        check(result.isNotEmpty()) { "Common query did not find an actual source record: $query" }
        println("$query -> ${result.size} results; ${result.first().id}: ${result.first().name}")
    }
    println("Full bundled asset: ${foods.size} records; ${foods.size - 103} known carbohydrate values; 103 unknown; $sourceCounts")
}
