# Android verification

## Update candidate for the existing 3.4.2.6 installation

The [local CI test report](android-update-readiness-2026-10-04.json) records **565
selected tests with no failures, errors or skips** at source commit `edcf1d5`.
It includes the full command-queue test class: 23 tests, including repeated
status requests, SMB rejection while a manual bolus is pending/performing, and
repeated cancellation without replay. No queue command was executed on a
physical pump. The [sanitized JUnit report](android-update-readiness-2026-10-04.xml)
contains the results; unchanged tasks were reused by Gradle where appropriate.
The earlier 542-test APK report below remains tied to its original binary.

An isolated [signed validation workflow](../../tools/ci/README.md) now uses the
existing Actions key, verifies its public certificate, and uploads only a uniquely
named candidate and public reports. It neither uploads to Google Drive nor
replaces the original APK. On 2026-10-04 both available GitHub write paths returned
HTTP 403, so the workflow was **not dispatched** and an Actions-signed update
candidate is **not available** from this session. The complete source patch and
workflow are ready for a checkout with the required GitHub write access.

The supplied phone/pump configuration is AAPS 3.4.2.6, OnePlus 15, xDrip+ BG,
OrangePro (RileyLink), and Medtronic 722 in an existing closed loop. Source
compatibility does not establish operation of that setup. No attached phone,
installed-APK certificate comparison or physical delivery test was available.
The [722 guide](../medtronic-722-setup.md) describes backup, update identity and
the limits of a first device check.

## Existing local debug APK

The current native build keeps the two bottom actions visible in all overview skins. “Enter insulin” opens the protected `InsulinDialog`. “Enter carbs” opens the new `FoodEntryDialog`: offline USDA search, local meal-description parsing, local Russian/English OCR and a trained local Food-101 classifier. EAN/UPC recognition is local; the separate Open Food Facts lookup needs network and sends the barcode only. Verified portions enter the existing protected `WizardDialog`. Manual carbohydrate entry inside the food form also opens this calculator, so it recommends insulin from the active loaded profile rather than requiring a manually chosen dose. No recognition result sends a bolus.

The food form rejects unknown nutrition, incompatible grams/millilitres, unresolved components and mixed carbohydrate conventions. It sums full-precision portions and rounds once at the existing integer Wizard boundary, checks AAPS limits and pump/profile availability, and invalidates stale confirmations. Wizard input restoration preserves edits, including zero carbs and cleared notes. Sources, limitations and reproduction are documented in [native food entry](../food-entry-native.md).

For Medtronic 722, attempted refreshes, initialization and UI result processing no longer mark failed queries as successful communication. The existing validated radio-response path updates the live and persisted clock. Regression tests cover failed repeated polls, initialization, unsuccessful UI results and transport responses. This makes the existing 20/40/60-minute reminder clock reflect actual communication. The [722 setup guide](../medtronic-722-setup.md) covers OrangeLink / RileyLink and the pump-specific radio band.

The current `fullDebug` build completed successfully with **542 targeted Gradle tests, zero failures, errors or skips**:

| Module | Tests |
| --- | ---: |
| Food catalog, parsing, OCR, calculation and Wizard prefills | 59 |
| Active-profile bolus recommendation, IOB and constraints | 8 |
| Medtronic driver and communication-clock regression | 148 |
| RileyLink transport | 327 |

The eight recommendation tests exercise the actual `BolusWizard`: active-profile IC/ISF, glucose relative to both target bounds, bolus and basal IOB, configured maximum bolus and absence of a pump command during calculation. Scheduled basal is not added to a meal bolus with superbolus disabled. The old test fixture incorrectly assigned the lower target twice; both target bounds are now tested with mg/dL fixtures. These are JVM calculations, not clinical validation or a native UI test.

The [build manifest](android-carb-profile-build-2026-10-04.json) records the exact production source commit, command, APK/source/asset hashes, native library alignment and limits. The [JUnit report](android-carb-profile-build-2026-10-04.xml) preserves every result; system properties and captured output were removed. The four official-catalog importer tests also passed. Host recognition evidence uses the exact bundled models: two synthetic nutrition-label fixtures and five real Food-101 validation photos. These checks are smoke checks, not a general accuracy estimate or an Android camera test.

The final APK is `AndroidAPS-food-profile-debug.apk`, SHA-256 `43a9c29e696e96364df0ea5a4fb9c7877ec1247f3884a83790df754b3ccf7a5f`. Its APK v2 signature, ZIP CRC, 16 KiB ZIP alignment and all 18 arm64/x86_64 native library load alignments passed. All 14 packaged food assets match their committed source bytes. The native minimum is Android 12. APK packaging and alignment do not establish runtime behavior on OnePlus 15.

This APK has the same package and signing certificate as the preceding food APK. The environment debug certificate may differ from another existing AAPS installation. An update requires the same signing key. If signatures differ, apply the source patch and build with the existing key; do not remove a working therapy installation to try this APK. No physical pump or phone was connected, and no insulin delivery was performed. Native rendering, camera lifecycle, notification delivery and Bluetooth behavior still require device verification. The insulin formulas, dosing settings, command queue and Medtronic bolus/profile delivery section are unchanged.

## Reproduce the current build

Use JDK 21, Android SDK 36 and Build Tools 35.0.0 from a committed checkout. Dependencies must first be available; the verified run used `--offline` after resolving them.

```sh
./gradlew -I tools/verification/memory-limits.gradle \
  '-Dorg.gradle.jvmargs=-Xmx3g -XX:ActiveProcessorCount=2 -XX:+UseParallelGC -Xss1024m' \
  -Pkotlin.compiler.execution.strategy=in-process -Pksp.incremental=false \
  --no-parallel --max-workers=2 \
  :ui:testFullDebugUnitTest --tests 'app.aaps.ui.food.*' \
    --tests 'app.aaps.ui.dialogs.FoodEntryCalculationTest' \
    --tests 'app.aaps.ui.dialogs.WizardInputPrefillTest' \
  :implementation:testFullDebugUnitTest \
    --tests 'app.aaps.implementation.wizard.BolusWizardTest' \
  :pump:medtronic:testFullDebugUnitTest \
  :pump:rileylink:testFullDebugUnitTest \
  :app:assembleFullDebug --no-daemon --console=plain
```

## Earlier evidence

The preceding native food build passed 534 tests before the manual-carbohydrate route was changed. Its [manifest](android-food-build-2026-10-04.json) and [JUnit report](android-food-build-2026-10-04.xml) remain tied to that earlier source and APK.

The preceding notification/SMB change passed **72 targeted Gradle tests**: APS SMB calculator 25, SMB command execution 8, notification delivery policy 6, and keep-alive/reminder/recovery guards 33. The [earlier manifest](android-gradle-2026-10-04.json) and [JUnit report](android-gradle-2026-10-04.xml) record that source and run. They remain historical evidence; these suites were not rerun for the food build. The [earlier action build](android-actions-build-2026-10-04.json) predates the native food route.

The unchanged offline overview HTML passed **156 Chromium checks** and the separate food concept passed **178**. Both are browser demonstrations with in-memory entries and no pump commands; their checks do not establish native Android rendering. See [design verification](../../design/browser-verification.json), [food concept verification](../../design/food-browser-verification.json) and [preview](../../design/overview-preview.png).
