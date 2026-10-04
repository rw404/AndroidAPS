# Local package-label recognition

`LocalFoodRecognizer` performs real Russian / English Tesseract OCR and ZXing GTIN decoding. It accepts a granted local image URI or a bitmap. It does not upload images, request a remote model, recognise ingredients or portion weights, calculate insulin, or send pump commands. Images are decoded with orientation handling and a longest edge of 2200 pixels; owned copies are recycled after recognition. A caller-supplied bitmap remains caller-owned.

The OCR engine runs on a serial worker. Cancellation stops the interruptible `getHOCRText` recognition, releases native resources, and discards the result; the UTF8 getter reads the subsequent recognised-text cache. The API has a 30-second timeout. Native image decoding / engine initialisation are not themselves interruptible, so a cancelled worker may briefly finish that operation before cleanup. Queued cancelled requests do not start recognition. No image or OCR text is persisted by this class.

Bundled `tessdata_fast` Russian and English assets total 7,974,826 bytes. They are installed in `Context.noBackupFilesDir/food/ocr/tessdata`, with size / SHA-256 verification. Their pinned source, license and hashes are recorded in `ui/src/main/assets/food/ocr/SOURCE.json`, with the Apache 2.0 license alongside the models.

Required dependencies, added by the integrating module owner:

- `cz.adaptech.tesseract4android:tesseract4android:4.9.0` from `https://jitpack.io`.
- `com.google.zxing:core:3.5.4` from Maven Central.
- Existing Kotlin coroutines and `javax.inject` dependencies.

The inspected Tesseract AAR SHA-256 is `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`. Every inspected `PT_LOAD` segment in its arm64-v8a and x86_64 JPEG / Leptonica / PNG / Tesseract libraries has alignment `0x4000` (16 KiB). Final APK ZIP alignment and actual execution on the target Android device still require verification.

`NutritionLabelParser` keeps explicit carbohydrate candidates separate from verified food data. It requires a gram amount and an unambiguous `per 100 g` basis to propose `carbohydrateGramsPer100g`; missing nutrition remains `null`. It does not substitute sugars, infer a denominator from package weight, convert millilitres or servings to grams, subtract fibre, repair ambiguous OCR digits, or treat inequalities as exact values. `TOTAL`, `AVAILABLE` and `UNSPECIFIED` definitions are kept separate, and every result requires review. Unknown, unsupported or conflicting input remains available as raw OCR text for manual correction. Returned raw text is bounded to 32,000 characters; oversized text is flagged `TEXT_TOO_LONG` and produces no numeric proposal.

JVM tests cover RU/EN labels, carbohydrate definitions, explicit zero versus missing data, package weight, per-serving / millilitre bases, dual columns, conflicting amounts, multiline OCR, unreadable digits, invalid physical amounts and approximate values. These tests verify the parser; they do not measure camera/OCR accuracy on a phone.

Fifteen parser tests passed in the focused JVM run. `ocr-model-smoke-report.json` records a separate real host OCR check: Tesseract 5.5.0 loaded these exact bundled Russian / English models, read two rendered synthetic labels, and its output passed through the production parser with expected values 12.5 and 20.4 g / 100 g. This confirms model loading and label extraction on those fixtures; Android native execution, photographs and target-device performance still need device testing.
