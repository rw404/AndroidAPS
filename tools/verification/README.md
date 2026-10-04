# Focused JVM verification harness

This harness runs real JUnit tests against selected production Kotlin sources when a complete Android build is unavailable. It does not replace Gradle, Android instrumentation, virtual-pump integration, device testing, or clinical validation.

The configured Android Gradle build was subsequently verified: 72 targeted tests passed and a debug APK was produced. See the [final Android verification](../../docs/verification/README.md).

Prerequisites: Python 3, Java 21, and a Gradle 9.0.0 distribution. Its `lib` directory supplies the Kotlin 2.2.0 compiler, standard library, reflection, Guava, and annotations. The AndroidAPS build uses Kotlin 2.2.21; the version difference is a verification limitation.

```sh
python3 tools/verification/download-dependencies.py --work-dir /tmp/aaps-verification
python3 tools/verification/run-jvm-harness.py \
  --gradle-lib /path/to/gradle-9.0.0/lib \
  --work-dir /tmp/aaps-verification \
  --java /path/to/jdk-21/bin/java
```

The download step uses Maven Central through the environment's normal proxy and TLS settings. It downloads pinned JUnit 6.0.1, Truth 1.4.5, Mockito 5.21.0, Mockito Kotlin 6.1.0, Byte Buddy 1.17.8, Objenesis 3.3, and javax.inject 1 dependencies. It needs ordinary network authorization. The runner uses those jars locally and writes generated sources/classes under `generated-jvm-harness`, with JUnit XML reports under `reports` in the work directory.

The runner compiles the exact repository sources for:

- `DetermineBasalSMB` and its 25 regression cases.
- `CommandSMBBolus` and its eight regression tests.
- `NotificationDeliveryPolicy`, the actual `Notification` model, and six regression tests.
- `PumpConnectionReminderPolicy` and its 16 tests for thresholds, deduplication, recovery and invalid timestamps.
- `PumpConnectionRecovery` and its four tests for pump/queue guards and accepted-request semantics.
- The actual pump-result interface/object, callback, command interface, progress-state object, time utility, constants, and glucose-unit enum.

Temporary APS DTO copies omit `@Serializable` annotations. The `RT` copy preserves the constructor fields but omits serialization methods. The harness does not test serialization.

Reduced external API stubs supply `ProfileUtil`, `FabricPrivacy`, `APSResult.Algorithm`, bolus-record/request DTOs, persistence, pump/plugin, command queue, logger, resource helper, date utility, preferences, injection interfaces, Android `Context`, the `RawRes` annotation, and resource IDs. Mockito controls those external collaborators in the original test sources. The stubs do not implement dosing or command guards. They cannot verify full interface linkage, generated Dagger wiring, Android resources/lifecycle, or real pump behavior. `KeepAliveWorkerTest` is an Android integration test class and is not compiled or run by this harness.

Observed on October 4, 2026: **59 tests passed; none skipped or failed.** The [verification manifest](../../docs/verification/jvm-harness-2026-10-04.json) records the command, source hashes and limitations; the [JUnit report](../../docs/verification/jvm-harness-2026-10-04.xml) preserves all 59 test results. System properties and captured system output were removed from the saved report. The previous [39-test result](../../docs/verification/jvm-harness-2026-10-03.json) is preserved separately. The synthetic prediction fixtures isolate safeguards and are not physiological simulations. Use the repository's Gradle module test tasks and replay suite to complete verification in a configured Android environment.
