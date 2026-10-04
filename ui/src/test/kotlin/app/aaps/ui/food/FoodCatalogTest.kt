package app.aaps.ui.food

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringReader

class FoodCatalogTest {

    @Test
    fun `Russian aliases find real foods without changing original names`() {
        val index = FoodSearchIndex(fixtures())
        assertEquals("usda:2512381", index.search("рис сырой").single().id)
        assertEquals("usda:168878", index.search("рис варёный").single().id)
        assertEquals("Soup, borscht", index.search("борщ").single().name)
        assertEquals("Rice pilaf", index.search("плов").single().name)
        assertEquals("usda:168878", index.search("white rice cooked").single().id)
        assertTrue(index.search("unmatched-food-name").isEmpty())
        assertTrue(index.search("").isEmpty())
    }

    @Test
    fun `the requested food ranks before another food containing the same ingredient`() {
        val index = FoodSearchIndex(fixtures() + FoodCatalogParser.read(StringReader(rankingFixtureText())))
        assertEquals("usda:171287", index.search("яйцо").first().id)
        assertEquals("usda:172673", index.search("хлеб").first().id)
        assertEquals(2, index.search("яйцо").size)
        assertTrue(index.search("рис сырой").none { it.id == "usda:169742" })
        assertEquals("usda:169742", index.search("рис сухой").single().id)
    }

    @Test
    fun `common inflections and with connector find actual composite names but not excluded ingredients`() {
        val index = FoodSearchIndex(FoodCatalogParser.read(StringReader(rankingFixtureText())))
        assertEquals("usda:170686", index.search("гречневая каша вареная").single().id)
        assertEquals("usda:173905", index.search("овсяная каша вареная").single().id)
        assertEquals("usda:2706537", index.search("курица с рисом").single().id)
        assertTrue(index.search("рис без курицы").isEmpty())
    }

    @Test
    fun `offline searches load once off caller thread and never query barcode service`() = runBlocking {
        var reads = 0
        val caller = Thread.currentThread()
        val catalog = FoodCatalog(
            readerFactory = {
                assertTrue(Thread.currentThread() !== caller)
                reads++
                StringReader(fixtureText())
            },
            barcodeClient = FoodBarcodeClient { error("Offline search must never send a network request") }
        )
        assertEquals(5, catalog.size())
        assertEquals("usda:168878", catalog.search("рис вареный").single().id)
        catalog.search("борщ")
        assertEquals(1, reads)
    }

    @Test
    fun `explicit valid barcodes are cached while descriptions never reach barcode service`() = runBlocking {
        var calls = 0
        val catalog = FoodCatalog(
            readerFactory = { StringReader(fixtureText()) },
            barcodeClient = FoodBarcodeClient { barcode ->
                assertEquals("3017620422003", barcode)
                calls++
                BarcodeLookupResult.NotFound
            }
        )
        assertTrue(catalog.findBarcode("борщ 150 г") is BarcodeLookupResult.Failure)
        assertTrue(catalog.findBarcode("123") is BarcodeLookupResult.Failure)
        assertTrue(catalog.findBarcode("3017620422003/secret") is BarcodeLookupResult.Failure)
        assertEquals(0, calls)
        assertEquals(BarcodeLookupResult.NotFound, catalog.findBarcode("3017620422003"))
        assertEquals(BarcodeLookupResult.NotFound, catalog.findBarcode("3017620422003"))
        assertEquals(1, calls)
    }

    private fun fixtures() = FoodCatalogParser.read(StringReader(fixtureText()))
    private fun fixtureText() = requireNotNull(javaClass.getResourceAsStream("/food/usda_fixtures.tsv")).bufferedReader().use { it.readText() }
    private fun rankingFixtureText() = requireNotNull(javaClass.getResourceAsStream("/food/usda_ranking_fixtures.tsv")).bufferedReader().use { it.readText() }
}
