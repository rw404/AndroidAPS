package app.aaps.ui.food

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** This service sends only a validated barcode. Photos and free-form text never enter it. */
internal class OpenFoodFactsBarcodeClient : FoodBarcodeClient {

    override fun lookup(barcode: String): BarcodeLookupResult {
        val fields = "code,product_name,product_name_ru,brands,nutriments,nutrition_data_per,nutrition_data_prepared,serving_size,data_quality_errors_tags"
        var connection: HttpURLConnection? = null
        return try {
            val request = URL("https://world.openfoodfacts.org/api/v3/product/$barcode?fields=$fields").openConnection() as HttpURLConnection
            connection = request
            request.connectTimeout = 12_000
            request.readTimeout = 12_000
            request.instanceFollowRedirects = false
            request.setRequestProperty("Accept", "application/json")
            request.setRequestProperty("User-Agent", "AndroidAPS-FoodCatalog/1.0 (https://github.com/rw404/AndroidAPS)")
            when (request.responseCode) {
                404 -> BarcodeLookupResult.NotFound
                429 -> BarcodeLookupResult.Failure("Open Food Facts rate limit reached. Try again later.")
                200 -> {
                    val response = request.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val text = StringBuilder()
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (text.length + count > MAX_RESPONSE_CHARACTERS) throw IOException("Product response is too large")
                            text.append(buffer, 0, count)
                        }
                        text.toString()
                    }
                    OpenFoodFactsProductParser.parse(response, barcode)
                }
                else -> BarcodeLookupResult.Failure("Open Food Facts is temporarily unavailable.")
            }
        } catch (_: IOException) {
            BarcodeLookupResult.Failure("Could not retrieve this barcode. The offline catalog remains available.")
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARACTERS = 1_000_000
    }
}

object OpenFoodFactsProductParser {

    fun parse(json: String, requestedBarcode: String): BarcodeLookupResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val product = root.getAsJsonObject("product")
            if (product == null) BarcodeLookupResult.NotFound else {
                val name = product.string("product_name_ru") ?: product.string("product_name")
                if (name == null) BarcodeLookupResult.Failure("The product has no usable name.") else {
                    val barcode = product.string("code")
                    if (barcode == null || barcode != requestedBarcode || barcode.length !in setOf(8, 12, 13, 14) || barcode.any { it !in '0'..'9' })
                        return BarcodeLookupResult.Failure("The returned product does not match the requested barcode.")
                    val nutrients = product.getAsJsonObject("nutriments")
                    val warnings = buildList {
                        add("Open Food Facts: community supplied data. Verify the package nutrition label.")
                        add("Confirm whether values refer to 100 g or 100 ml and which carbohydrate definition is used.")
                        if (nutrients?.number("carbohydrates_100g") == null) add("Carbohydrate amount is unavailable; it is not zero.")
                        if (product.getAsJsonArray("data_quality_errors_tags")?.size()?.let { it > 0 } == true) add("Open Food Facts reports data quality errors for this product.")
                    }
                    BarcodeLookupResult.Found(
                        FoodProduct(
                            id = "off:$barcode",
                            name = name,
                            source = FoodSource.OPEN_FOOD_FACTS,
                            sourceUrl = "https://world.openfoodfacts.org/product/$barcode",
                            // Country tags or the _100g field name do not establish the package's label convention.
                            basis = NutritionBasis.UNKNOWN,
                            carbohydrateDefinition = CarbohydrateDefinition.UNKNOWN,
                            preparation = FoodPreparation.AS_SOLD,
                            carbohydrateGrams = nutrients?.number("carbohydrates_100g"),
                            fiberGrams = nutrients?.number("fiber_100g"),
                            fatGrams = nutrients?.number("fat_100g"),
                            proteinGrams = nutrients?.number("proteins_100g"),
                            energyKcal = nutrients?.number("energy-kcal_100g"),
                            barcode = barcode,
                            warnings = warnings
                        )
                    )
                }
            }
        } catch (_: RuntimeException) {
            BarcodeLookupResult.Failure("The product response could not be read.")
        }
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}
