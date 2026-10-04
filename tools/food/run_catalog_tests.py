#!/usr/bin/env python3
"""Compile the real catalog sources/tests, using Android asset type stubs only.

This is a focused JVM fallback for environments that cannot run the full Android
Gradle build. It neither builds an APK nor validates Android lifecycle behaviour.
Dependencies must already exist in a Gradle cache; nothing is downloaded here.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def cached(cache: Path, group: str, artifact: str, version: str) -> Path:
    candidates = sorted((cache / "caches/modules-2/files-2.1" / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if not candidates:
        raise SystemExit(f"Missing cached dependency {group}:{artifact}:{version}; run the repository Gradle dependency resolution first.")
    return candidates[0]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-cache", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    parser.add_argument("--java", default=str(Path(os.environ.get("JAVA_HOME", "")) / "bin/java") if os.environ.get("JAVA_HOME") else "java")
    parser.add_argument("--junit-console", type=Path, required=True, help="JUnit Platform console standalone 6.0.1 jar")
    args = parser.parse_args()
    if not args.junit_console.is_file():
        raise SystemExit("JUnit console jar was not found")

    compiler = cached(args.gradle_cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21")
    stdlib = cached(args.gradle_cache, "org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    reflect = cached(args.gradle_cache, "org.jetbrains.kotlin", "kotlin-reflect", "2.2.21")
    script_runtime = cached(args.gradle_cache, "org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21")
    gson = cached(args.gradle_cache, "com.google.code.gson", "gson", "2.13.2")
    coroutines = cached(args.gradle_cache, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
    annotation_candidates = sorted((args.gradle_cache / "caches/modules-2/files-2.1/org.jetbrains/annotations").glob("*/*/annotations-*.jar"))
    if not annotation_candidates:
        raise SystemExit("Missing cached org.jetbrains:annotations dependency")
    annotations = annotation_candidates[-1]
    classpath = os.pathsep.join(map(str, [stdlib, gson, coroutines, annotations, args.junit_console]))
    compiler_classpath = os.pathsep.join(map(str, [compiler, stdlib, reflect, script_runtime, coroutines, annotations]))
    production = ROOT / "ui/src/main/kotlin/app/aaps/ui/food"
    tests = ROOT / "ui/src/test/kotlin/app/aaps/ui/food"
    sources = [production / f"{name}.kt" for name in ["FoodProduct", "FoodCatalogParser", "FoodSearchIndex", "FoodCatalog", "OpenFoodFactsBarcodeClient"]]
    sources += [tests / f"{name}Test.kt" for name in ["FoodProduct", "FoodCatalogParser", "FoodCatalog", "OpenFoodFactsProductParser"]]

    with tempfile.TemporaryDirectory(prefix="aaps-food-tests-") as temp:
        work = Path(temp)
        context_stub = work / "Context.kt"
        context_stub.write_text("""package android.content
import android.content.res.AssetManager
abstract class Context {
    abstract val applicationContext: Context
    abstract val assets: AssetManager
}
""", encoding="utf-8")
        assets_stub = work / "AssetManager.kt"
        assets_stub.write_text("""package android.content.res
import java.io.InputStream
abstract class AssetManager { abstract fun open(name: String): InputStream }
""", encoding="utf-8")
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([args.java, "-cp", compiler_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", classpath, "-d", str(classes), *map(str, sources), str(context_stub), str(assets_stub), str(ROOT / "tools/food/CatalogSmoke.kt")], check=True, cwd=ROOT)
        shutil.copytree(ROOT / "ui/src/test/resources/food", classes / "food")
        runtime = os.pathsep.join([str(classes), classpath])
        subprocess.run([args.java, "-jar", str(args.junit_console), "execute", "--class-path", runtime, "--select-class", "app.aaps.ui.food.FoodProductTest", "--select-class", "app.aaps.ui.food.FoodCatalogParserTest", "--select-class", "app.aaps.ui.food.FoodCatalogTest", "--select-class", "app.aaps.ui.food.OpenFoodFactsProductParserTest", "--fail-if-no-tests", "--details", "tree"], check=True, cwd=ROOT)
        subprocess.run([args.java, "-cp", runtime, "app.aaps.ui.food.CatalogSmokeKt", str(ROOT / "ui/src/main/assets/food/usda_foods.tsv")], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
