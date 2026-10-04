"""Regression checks for real pinned USDA records and archive normalization."""

import csv
import json
import pathlib
import unittest

import build_usda_catalog as builder


ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "ui/src/main/assets/food"
FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures/usda_examples.tsv"
CACHE = pathlib.Path("/tmp/aaps-food-usda-cache")


def read_tsv(path):
    with path.open(encoding="utf-8", newline="") as handle:
        return {row["id"]: row for row in csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE, quotechar=None)}


class UsdaCatalogTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.foods = read_tsv(ASSET_DIR / "usda_foods.tsv")
        cls.fixtures = read_tsv(FIXTURES)
        cls.manifest = json.loads((ASSET_DIR / "manifest.json").read_text(encoding="utf-8"))

    def test_exact_real_source_examples_are_preserved_in_asset(self):
        self.assertEqual(len(self.fixtures), 4)
        for food_id, fixture in self.fixtures.items():
            self.assertEqual(self.foods[food_id], fixture)
        self.assertEqual(self.fixtures["2512381"]["preparation"], "RAW")
        self.assertEqual(self.fixtures["168878"]["preparation"], "COOKED")
        self.assertEqual(self.fixtures["2710105"]["preparation"], "UNKNOWN")

    def test_archive_normalization_matches_real_examples_including_fndds_keys(self):
        sources = json.loads((ROOT / "tools/food/usda_sources.json").read_text(encoding="utf-8"))["sources"]
        if not all((CACHE / source["url"].rsplit("/", 1)[1]).exists() for source in sources):
            self.skipTest("Download the pinned archives to the default cache for source-level checks")
        for source in sources:
            archive = CACHE / source["url"].rsplit("/", 1)[1]
            self.assertEqual(builder.sha256(archive), source["sha256"])
            normalized, metadata = builder.normalize(source, archive)
            by_id = {row["id"]: row for row in normalized}
            for food_id, fixture in self.fixtures.items():
                if fixture["dataType"] == source["dataType"]:
                    self.assertEqual({key: by_id[food_id].get(key, "") for key in builder.COLUMNS}, fixture)
            if source["dataType"] == "survey_fndds_food":
                self.assertEqual(metadata["foodsWithCarbs"], 5431)
                self.assertEqual(metadata["sourceNutrientKeyToCanonicalId"]["205"], "1005")

    def test_unknown_nutrients_and_actual_zero_remain_distinct(self):
        self.assertEqual(self.foods["2727566"]["carbs"], "")
        self.assertEqual(self.foods["334536"]["fiber"], "")
        self.assertEqual(self.foods["2705383"]["carbs"], "")
        self.assertEqual(self.foods["2705849"]["carbs"], "0")
        quarantine = [value for source in self.manifest["sources"] for value in source["invalidNutrientValues"]]
        self.assertTrue(any(value["fdcId"] == "2727566" and value["rawAmount"] == "-0.47505" for value in quarantine))

    def test_asset_manifest_and_physical_macro_ranges(self):
        self.assertEqual(len(self.foods), 13694)
        self.assertEqual(self.manifest["foodCount"], len(self.foods))
        self.assertEqual(self.manifest["assetSha256"], builder.sha256(ASSET_DIR / "usda_foods.tsv"))
        for row in self.foods.values():
            self.assertEqual(row["sourceUrl"], f"https://fdc.nal.usda.gov/food-details/{row['id']}/nutrients")
            for field in ["carbs", "fiber", "fat", "protein"]:
                if row[field]:
                    self.assertGreaterEqual(float(row[field]), 0)
                    self.assertLessEqual(float(row[field]), 100)
        for line in (ASSET_DIR / "usda_foods.tsv").read_text(encoding="utf-8").splitlines():
            self.assertEqual(len(line.split("\t")), 10)


if __name__ == "__main__":
    unittest.main()
