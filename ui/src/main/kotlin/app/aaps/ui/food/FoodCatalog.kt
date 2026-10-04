package app.aaps.ui.food

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Reader

/** Offline search. Only an explicit barcode lookup can make a network request. */
class FoodCatalog internal constructor(
    private val readerFactory: () -> Reader,
    private val barcodeClient: FoodBarcodeClient
) {

    constructor(context: Context) : this(
        context.applicationContext.assets.let { assets -> { assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8) } },
        OpenFoodFactsBarcodeClient()
    )

    private val loadMutex = Mutex()
    private val barcodeMutex = Mutex()
    @Volatile private var index: FoodSearchIndex? = null
    private val barcodeCache = LinkedHashMap<String, BarcodeLookupResult>()

    suspend fun search(query: String, limit: Int = 60): List<FoodProduct> = withContext(Dispatchers.IO) {
        loadedIndex().search(query, limit.coerceIn(1, 200))
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) { loadedIndex().size }

    suspend fun findBarcode(barcode: String): BarcodeLookupResult = withContext(Dispatchers.IO) {
        val normalized = barcode.trim()
        if (normalized.length !in setOf(8, 12, 13, 14) || normalized.any { !it.isDigit() || it !in '0'..'9' })
            return@withContext BarcodeLookupResult.Failure("Enter an 8, 12, 13 or 14 digit product barcode.")
        barcodeMutex.withLock {
            barcodeCache[normalized]?.let { return@withLock it }
            barcodeClient.lookup(normalized).also { result ->
                if (result !is BarcodeLookupResult.Failure) {
                    if (barcodeCache.size >= 100) barcodeCache.remove(barcodeCache.keys.first())
                    barcodeCache[normalized] = result
                }
            }
        }
    }

    private suspend fun loadedIndex(): FoodSearchIndex = index ?: loadMutex.withLock {
        index ?: FoodSearchIndex(FoodCatalogParser.read(readerFactory())).also { index = it }
    }

    companion object {
        const val ASSET_NAME = "food/usda_foods.tsv"
    }
}

internal fun interface FoodBarcodeClient {
    fun lookup(barcode: String): BarcodeLookupResult
}
