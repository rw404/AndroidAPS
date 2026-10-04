# Offline USDA food catalog

The bundled `ui/src/main/assets/food/usda_foods.tsv` contains real catalog food
records from USDA FoodData Central Foundation Foods (2026-04-30), SR Legacy
(2018-04), and FNDDS (2024-10-31; survey 2021–2023). It includes individual foods
and composite dishes. Original English descriptions and numeric FDC IDs are
preserved. Russian search aliases belong to the app, not to this dataset.

USDA's official [download page](https://fdc.nal.usda.gov/download-datasets) lists
the ZIP URLs in `usda_sources.json`. The older `/data-downloads/` URL returns 404.
All three CSV archives are public and need no API key. Their pinned SHA-256
digests reject unexpected archive changes. ZIPs stay in the local build cache
and are not bundled in the app.

Rebuild with Python 3 and the standard library:

```sh
python3 tools/food/build_usda_catalog.py
python3 -m unittest discover -s tools/food -p 'test_*.py' -v
```

Optional flags: `--cache-dir`, `--output-dir`, `--sources`, `--timeout` (socket
timeout), `--retries`, `--download-only`. HTTP uses the inherited proxy and CA
configuration. TLS verification stays enabled. Downloads are streamed and can
be repeated safely from the cache. To update the release, verify the official
download links, update versions/URLs/checksums intentionally, rebuild, and
review the resulting manifest and real-source fixtures.

The TSV is UTF-8, one header and one record per line, with ten tab-separated
fields, no CSV quoting, and no tabs/newlines inside a field:

```
id name dataType carbs fiber fat protein energyKcal preparation sourceUrl
```

Nutrients refer to **100 g edible portion**, not one serving or 100 ml. Carbs
are USDA total carbohydrate by difference (canonical nutrient 1005), **including
fiber**. No EU available-carbohydrate conversion or fiber subtraction is made.
Protein/fat/fiber use 1003/1004/1079. Energy uses recorded kcal in priority order
1008, 2048 (Atwater specific), 2047 (Atwater general); it is not recomputed from
macros. For this FNDDS CSV release, `food_nutrient.nutrient_id` contains legacy
`nutrient_nbr` keys (205, 203, 204, 291, 208), which the builder resolves through
the archive's own `nutrient.csv`. Foundation and SR use canonical IDs.

Empty cells mean **unknown**; measured source zeros remain `0`. Non-finite,
negative and physically impossible macro values over 100 g/100 g become unknown
without clipping; their original values and source IDs remain in the manifest.
Food identities are retained even when carbohydrate data are missing. Foundation
sampling/acquisition support rows are counted separately, because they are not
catalog `foundation_food` records. They are not offered as selectable foods.

Preparation is inferred only from explicit raw/cooking words. Ambiguous names
remain `UNKNOWN`, including recipe names such as `Soup, borscht`. The description
does not prove a user's recipe, ingredients, serving size, or preparation. The
catalog supplies nutrition data for a reviewed food draft, not an insulin dose.

`manifest.json` records versions, URLs, input/output checksums, row counts,
quarantined values, nutrient-key mappings, units and missing-value semantics.
`fixtures/usda_examples.tsv` contains four exact downloaded records: raw rice,
cooked rice, a composite FNDDS dish, and a Foundation record with unknown carbs.
The tests compare these against both the asset and the pinned source archives.

## Native catalog and focused JVM checks

`FoodCatalog.search(query)` and `size()` load/cache the asset on `Dispatchers.IO`.
Construction performs no disk IO. Russian aliases expand common ingredient and
preparation queries; original English food names stay visible. The catalog is
not a database of every household recipe or Russian package. A composite USDA
dish still requires a user to review whether the recipe and preparation match.

`findBarcode(barcode)` is the only catalog network operation. It accepts only an
8, 12, 13 or 14 digit barcode, makes an explicit Open Food Facts v3 product read,
and supplies an application User-Agent. It sends neither a meal description nor
a photo. The returned product code must equal the requested code. Results are
cached in memory within this catalog instance; first-time barcode lookup needs
internet, and this iteration has no persistent packaged-product database.

Open Food Facts values retain their source, barcode and community-data warnings.
Their nutrition basis and carbohydrate definition start `UNKNOWN`: the `_100g`
API field alone does not prove the user's package uses grams rather than
milliliters, or US total rather than available carbohydrate. A label review must
explicitly establish those before a draft can use them. Unknown carbohydrate is
never replaced with zero. No fiber subtraction is performed. The real saved OFF
fixture is public product `3017620422003`, fetched on 2026-10-04; its source and
license are recorded in `ui/src/test/resources/food/fixture_sources.json`.

The repository Android unit tests are under `ui/src/test/kotlin/app/aaps/ui/food`.
For an environment without a working Android Gradle build, a focused fallback
compiles the five real catalog production files and four real test classes with
Kotlin 2.2.21, Gson 2.13.2 and coroutines 1.10.2 from an existing Gradle cache:

```sh
python3 tools/food/run_catalog_tests.py \
  --gradle-cache "$GRADLE_USER_HOME" \
  --java "$JAVA_HOME/bin/java" \
  --junit-console /path/to/junit-platform-console-standalone-6.0.1.jar
```

The only stubs are the Android `Context` and `AssetManager` types, with abstract
properties/open and no implementation. Tests inject real source fixtures via a
reader and a counting barcode client. The runner also parses **all 13,694 bundled
records**, checks unique IDs, source counts and 103 unknown carbohydrates, then
searches common Russian/English queries. It does not test the native dialog,
Android lifecycle, network availability, image recognition or insulin delivery.
On 2026-10-04 the focused run passed **18/18 JUnit tests** and the complete asset
smoke check; the archive/normalizer suite passed **4/4 Python tests**. Actual
downloaded source bytes total **13,226,025**. This establishes data provenance
and code invariants, not recipe accuracy or clinical accuracy.

Open Food Facts database material is separate from the CC0 USDA bulk catalog:
see [Open Food Facts reuse terms](https://world.openfoodfacts.org/terms-of-use),
[ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) and
[Database Contents License](https://opendatacommons.org/licenses/dbcl/1-0/).
API reference: https://openfoodfacts.github.io/openfoodfacts-server/api/.

USDA states on the [FoodData Central homepage](https://fdc.nal.usda.gov/) that
these data are in the public domain and published under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). Suggested attribution:
U.S. Department of Agriculture, Agricultural Research Service, Beltsville Human
Nutrition Research Center. FoodData Central. https://fdc.nal.usda.gov/.
