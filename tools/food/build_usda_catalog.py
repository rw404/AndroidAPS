#!/usr/bin/env python3
"""Build the offline food catalog from pinned, public USDA CSV archives.

Only the Python standard library is needed. urllib honors the inherited HTTP(S)
proxy and CA configuration; downloads need no FoodData Central API key.
"""

import argparse
import concurrent.futures
import csv
import datetime
import decimal
import hashlib
import io
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile
from collections import Counter


ROOT = pathlib.Path(__file__).resolve().parents[2]
COLUMNS = ["id", "name", "dataType", "carbs", "fiber", "fat", "protein", "energyKcal", "preparation", "sourceUrl"]
NUTRIENTS = {"1005": "carbs", "1079": "fiber", "1004": "fat", "1003": "protein", "1008": "energyKcal", "2047": "energyGeneral", "2048": "energySpecific"}
EXPECTED_UNITS = {key: "KCAL" if key in {"1008", "2047", "2048"} else "G" for key in NUTRIENTS}
RAW = re.compile(r"\braw\b", re.IGNORECASE)
COOKED = re.compile(r"\b(?:cooked|boiled|baked|roasted|fried|steamed|grilled|braised|stewed|broiled|poached|sauteed|sautéed)\b", re.IGNORECASE)
NEGATED_COOKING = re.compile(r"\b(?:not|un|without)\s*(?:cooked|baked|fried|roasted)\b", re.IGNORECASE)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(source, cache_dir, timeout, retries):
    target = cache_dir / source["url"].rsplit("/", 1)[1]
    expected = source.get("sha256")
    if target.exists() and zipfile.is_zipfile(target):
        digest = sha256(target)
        if expected is None or digest == expected:
            print(f"Cached {target.name}: {target.stat().st_size} bytes, sha256={digest}", flush=True)
            return target
        raise ValueError(f"Cached archive checksum mismatch: {target}")
    request = urllib.request.Request(source["url"], headers={"User-Agent": "AndroidAPS offline food catalog builder (https://github.com/rw404/AndroidAPS)"})
    partial = target.with_suffix(target.suffix + ".part")
    for attempt in range(retries):
        try:
            digest = hashlib.sha256()
            with urllib.request.urlopen(request, timeout=timeout) as response, partial.open("wb") as output:
                content_length = response.headers.get("Content-Length")
                print(f"Downloading {target.name}, bytes={content_length or 'unknown'}", flush=True)
                byte_count = 0
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    output.write(chunk)
                    digest.update(chunk)
                    byte_count += len(chunk)
            if content_length and byte_count != int(content_length):
                raise IOError(f"Incomplete archive {target.name}: {byte_count}/{content_length}")
            if expected and digest.hexdigest() != expected:
                raise ValueError(f"Downloaded archive checksum mismatch: {target.name}")
            if not zipfile.is_zipfile(partial):
                raise ValueError(f"Download is not a ZIP archive: {target.name}")
            partial.replace(target)
            print(f"Downloaded {target.name}: {byte_count} bytes, sha256={digest.hexdigest()}", flush=True)
            return target
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            partial.unlink(missing_ok=True)
            if attempt + 1 == retries:
                raise
            print(f"Retry {attempt + 1}/{retries}: {target.name}: {error}", file=sys.stderr, flush=True)
            time.sleep(min(2 ** attempt, 8))
    raise RuntimeError("No download attempts configured")


def rows(archive, basename):
    members = [name for name in archive.namelist() if pathlib.PurePosixPath(name).name == basename]
    if len(members) != 1:
        raise ValueError(f"Expected exactly one {basename}; found {members}")
    with archive.open(members[0]) as binary, io.TextIOWrapper(binary, encoding="utf-8-sig", newline="") as handle:
        yield from csv.DictReader(handle)


def amount(value):
    if not value or not value.strip():
        return None
    number = decimal.Decimal(value.strip())
    if not number.is_finite() or number < 0:
        raise ValueError(f"Invalid source nutrient amount: {value!r}")
    return format(number.normalize(), "f")


def preparation(description):
    raw = bool(RAW.search(description))
    cooked = bool(COOKED.search(description)) and not NEGATED_COOKING.search(description)
    if raw == cooked:
        return "UNKNOWN"
    return "RAW" if raw else "COOKED"


def normalize(source, path):
    foods = {}
    other_types = Counter()
    invalid_values = []
    with zipfile.ZipFile(path) as archive:
        for food in rows(archive, "food.csv"):
            if food["data_type"] != source["dataType"]:
                other_types[food["data_type"]] += 1
                continue
            food_id = food["fdc_id"]
            if not food_id.isdigit() or food_id in foods:
                raise ValueError(f"Invalid or repeated FDC ID: {food_id}")
            foods[food_id] = {
                "id": food_id,
                "name": " ".join(food["description"].split()),
                "dataType": food["data_type"],
                "preparation": preparation(food["description"]),
                "sourceUrl": f"https://fdc.nal.usda.gov/food-details/{food_id}/nutrients",
            }
        if not foods:
            raise ValueError(f"No {source['dataType']} records in {path}")
        nutrient_definitions = list(rows(archive, "nutrient.csv"))
        units = {row["id"]: row["unit_name"].upper() for row in nutrient_definitions}
        # The pinned FNDDS CSV labels this field nutrient_id but stores legacy
        # nutrient_nbr values (205, 291, ...), unlike Foundation and SR Legacy.
        nutrient_key = source.get("nutrientKey", "id")
        source_nutrient_ids = {row[nutrient_key]: row["id"] for row in nutrient_definitions if row["id"] in NUTRIENTS}
        for nutrient_id, unit in EXPECTED_UNITS.items():
            if nutrient_id in units and units[nutrient_id] != unit:
                raise ValueError(f"Unexpected units for nutrient {nutrient_id}: {units[nutrient_id]}")
        duplicates = 0
        for nutrient in rows(archive, "food_nutrient.csv"):
            food = foods.get(nutrient["fdc_id"])
            canonical_nutrient_id = source_nutrient_ids.get(nutrient["nutrient_id"])
            field = NUTRIENTS.get(canonical_nutrient_id)
            if food is None or field is None:
                continue
            try:
                value = amount(nutrient["amount"])
                if value is not None and field in {"carbs", "fiber", "fat", "protein"} and decimal.Decimal(value) > 100:
                    raise ValueError("Macro exceeds 100 g per 100 g edible portion")
            except (ValueError, decimal.InvalidOperation):
                invalid_values.append({"fdcId": food["id"], "nutrientId": int(canonical_nutrient_id), "sourceNutrientKey": nutrient["nutrient_id"], "rawAmount": nutrient["amount"], "reason": "Non-finite, unparseable or outside physical range (macros: 0..100 g/100 g; energy: nonnegative) retained as unknown"})
                continue
            if value is None:
                continue
            if field in food:
                duplicates += 1
                if food[field] != value:
                    raise ValueError(f"Conflicting {field} measurements for {food['id']}: {food[field]}/{value}")
            food[field] = value
        for food in foods.values():
            if "energyKcal" not in food:
                if "energySpecific" in food:
                    food["energyKcal"] = food["energySpecific"]
                elif "energyGeneral" in food:
                    food["energyKcal"] = food["energyGeneral"]
    metadata = {
        **source,
        "archiveBytes": path.stat().st_size,
        "archiveSha256": sha256(path),
        "foodCount": len(foods),
        "foodsWithCarbs": sum("carbs" in food for food in foods.values()),
        "nonCatalogSupportingRowsByType": dict(sorted(other_types.items())),
        "duplicateEqualNutrientRows": duplicates,
        "invalidNutrientValues": invalid_values,
        "sourceNutrientKeyToCanonicalId": source_nutrient_ids,
    }
    print(f"Normalized {source['dataType']}: {metadata['foodCount']} foods; {metadata['foodsWithCarbs']} with carbs", flush=True)
    return list(foods.values()), metadata


def build(arguments):
    configuration = json.loads(arguments.sources.read_text(encoding="utf-8"))
    sources = configuration["sources"]
    arguments.cache_dir.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        paths = list(pool.map(lambda source: download(source, arguments.cache_dir, arguments.timeout, arguments.retries), sources))
    if arguments.download_only:
        return
    all_foods = {}
    metadata = []
    for source, path in zip(sources, paths):
        foods, details = normalize(source, path)
        for food in foods:
            if food["id"] in all_foods:
                raise ValueError(f"Duplicate FDC ID across datasets: {food['id']}")
            all_foods[food["id"]] = food
        metadata.append(details)
    arguments.output_dir.mkdir(parents=True, exist_ok=True)
    catalog = arguments.output_dir / "usda_foods.tsv"
    partial = catalog.with_suffix(".tsv.part")
    with partial.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, COLUMNS, delimiter="\t", extrasaction="ignore", lineterminator="\n", quoting=csv.QUOTE_NONE, quotechar=None)
        writer.writeheader()
        writer.writerows(sorted(all_foods.values(), key=lambda row: int(row["id"])))
    partial.replace(catalog)
    manifest = {
        "schemaVersion": 1,
        "dataset": "USDA FoodData Central: Foundation, SR Legacy and FNDDS",
        "license": "CC0-1.0",
        "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        "provenanceUrl": "https://fdc.nal.usda.gov/",
        "downloadPage": configuration["downloadPage"],
        "generatedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "asset": catalog.name,
        "assetSha256": sha256(catalog),
        "assetBytes": catalog.stat().st_size,
        "columns": COLUMNS,
        "foodCount": len(all_foods),
        "foodsWithCarbs": sum("carbs" in food for food in all_foods.values()),
        "preparationCounts": dict(sorted(Counter(food["preparation"] for food in all_foods.values()).items())),
        "basis": "per 100 g edible portion; not per serving or per 100 ml",
        "carbohydrate": {"nutrientId": 1005, "name": "Carbohydrate, by difference", "unit": "g", "convention": "USDA total carbohydrate includes dietary fiber; no EU available-carbohydrate conversion"},
        "nutrientIds": {field: int(nutrient) for nutrient, field in NUTRIENTS.items()},
        "energyPriority": [1008, 2048, 2047],
        "missingValues": "Empty TSV cells mean unknown, never zero. Source zeros remain zero.",
        "invalidValues": "Non-finite/negative nutrients and macros >100 g/100 g are unknown; original amounts and IDs remain in each source's invalidNutrientValues. No clipping to zero or 100. Energy kcal is not capped at 100.",
        "preparationInference": "Explicit raw or cooking words only; ambiguous raw+cooked and negated cooking are UNKNOWN. No recipe or food-name assumptions.",
        "sources": metadata,
    }
    (arguments.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(all_foods)} foods, {catalog.stat().st_size} bytes, sha256={manifest['assetSha256']}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=pathlib.Path, default=ROOT / "tools/food/usda_sources.json")
    parser.add_argument("--cache-dir", type=pathlib.Path, default=pathlib.Path("/tmp/aaps-food-usda-cache"))
    parser.add_argument("--output-dir", type=pathlib.Path, default=ROOT / "ui/src/main/assets/food")
    parser.add_argument("--timeout", type=float, default=60.0, help="HTTP socket timeout in seconds")
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--download-only", action="store_true")
    arguments = parser.parse_args()
    if arguments.retries < 1 or arguments.timeout <= 0:
        parser.error("retries and timeout must be positive")
    build(arguments)


if __name__ == "__main__":
    main()
