# SMB correctness review

Reviewed repository baseline: `48f9f6c`, October 2026. This change adds regression coverage and command validation. It does not change insulin calculations, sensitivity formulas, SMB fractions, pump increments, frequency settings, basal caps, or IOB limits. Synthetic test fixtures are not treatment recommendations.

## Correctness changes

`implementation/.../queue/commands/CommandSMBBolus.kt` validates the request immediately before pump delegation:

- Non-finite, zero, and negative insulin requests fail without a pump call. A positive request can become zero during the second constraints pass in `CommandQueueImplementation.bolus`; previously the command still delegated it. Some drivers, including the virtual pump and DanaRS, require positive insulin and throw for zero.
- Newly discovered bolus history invalidates the pending SMB. The queue already checks `lastKnownBolusTime` when enqueueing. The command now repeats that check at execution, covering history updates received while a command waits. A new bolus outside the frequency window must still invalidate a decision based on earlier history.
- One captured `DateUtil.now()` value drives both frequency and request-expiry checks. Previously expiry used `System.currentTimeMillis()` separately. Existing frequency and one-minute validity thresholds remain unchanged.

These changes prevent invalid command dispatch; they do not establish improved glucose outcomes. The input-validation gap is demonstrated by the code path and regression cases, not by measured real-world incident rates.

## Regression coverage

`plugins/aps/.../openAPSSMB/DetermineBasalSMBTest.kt` exercises the current algorithm's existing behavior:

- Permitted SMB calculation with whole-number deltas `0`, `1`, `2`, plus a fractional delta.
- Master SMB permission and high temporary-target suppression.
- Old/future readings, CGM error values, noise, flat readings, actual and predicted low glucose, and implausibly large jumps.
- Maximum IOB, remaining IOB budget, sub-increment amounts, downward pump-step rounding, and separate SMB/UAM basal caps.
- Recent/future boluses, the existing strict six-second tolerance boundary, and the existing one-to-ten-minute interval clamp.

The predicted-low case intentionally combines sustained activity with zero reported IOB to isolate the prediction safeguard. It tests branch behavior; it is not a physiological simulation.

The command regression tests cover fresh dispatch, non-positive/non-finite request rejection, history changes after enqueue, frequency boundaries, expiry boundaries, missing timestamps, and use of the injected clock.

The existing `app/src/androidTest/kotlin/app/aaps/ReplayApsResultsTest.kt` compares Kotlin with the JavaScript reference and recorded outputs. However, its SMB and DynISF paths return early for every whole-number delta (`floor(delta) == delta`). The new tests cover safeguard behavior for those inputs. The replay skip remains: removing it requires investigating the documented JavaScript integer/double interoperability difference and running the full dataset; it cannot be assumed compatible.

## Remaining findings

1. **Returned diagnostic lists share mutable state.** `DetermineBasalSMB` keeps `consoleLog` and `consoleError` as singleton fields, places the same list objects in every `RT`, and clears them at the next invocation. An earlier result's diagnostic lists therefore change on the next run. Snapshotting result logs would improve audit provenance without changing therapy. This is a source-verified issue; it is not repaired in this patch.
2. **Input validation inside the calculator is incomplete.** The calculator indexes `iob_data_array[0]` without checking for an empty array, requires every zero-temp IOB entry, and has scattered NaN checks instead of a complete finite-input contract. Dynamic-ISF TDD completeness checks non-nullness, while its formula divides by weighted TDD. Non-finite or zero values need explicit producer/consumer contract tests before selecting fallback behavior. Their occurrence with ordinary patient data was not established here. The new command guard prevents a non-finite SMB amount from reaching the pump; it does not validate every intermediate value or temp-basal output.
3. **Wall-clock rollback remains an expiry edge case.** The existing command accepts a future `deliverAtTheLatest` value. Clock rollback can extend validity. This patch preserves that behavior because selecting tolerances or switching to monotonic expiry requires checking the complete Loop/queue timing contract. Loop deliberately refreshes the SMB request time after TBR completion.
4. **Replay/device integration remains necessary.** JVM regression tests do not exercise history synchronization, Bluetooth timing, pump firmware, sensor behavior, Android lifecycle handling, or callback/queue recovery on real hardware.

## Verification and clinical validation

The final configured Android Gradle run on October 4, 2026 passed all 33 SMB regression cases against the repository's actual module dependencies and Kotlin 2.2.21. The complete targeted run passed 72 tests and produced a debug APK. See the [final Android verification](verification/README.md); no physiological or hardware validation was performed.

Run focused JVM tests in a configured Android/JDK environment:

```sh
./gradlew :plugins:aps:testFullDebugUnitTest --tests 'app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMBTest'
./gradlew :implementation:testFullDebugUnitTest --tests 'app.aaps.implementation.queue.commands.CommandSMBBolusTest'
```

Module task names depend on the configured build variant. Run the replay instrumentation suite with representative reference outputs on an emulator/device, followed by virtual-pump integration tests for history updates, queued manual boluses, expiry, and failure callbacks. Execution results for the current environment must be recorded separately; adding tests alone is not evidence that they passed.

Observed verification on October 3, 2026: **33/33 SMB tests passed, none skipped**, in a standalone Kotlin/JUnit harness (25 calculator cases and eight command tests, including slow history lookup). Six notification-policy tests also passed, for **39 total**. The harness compiled the exact production calculator/command and test sources, plus the actual callback, pump-result object/interface, time utility, progress-state sources, and notification model/policy. It used Kotlin 2.2.0, JUnit 6.0.1, Mockito 5.21.0, and Truth 1.4.5. DTO serialization annotations/methods were omitted from temporary copies; external Android, persistence, preference, resource, logging, and injection APIs used reduced stubs. This verifies the covered calculation/dispatch behavior, not a complete Android build or hardware integration. The repository's intended Kotlin version is 2.2.21. The reusable [harness instructions](../tools/verification/README.md) document the exact scope and stubs. The [verification manifest](verification/jvm-harness-2026-10-03.json) and [JUnit report](verification/jvm-harness-2026-10-03.xml) preserve the executed source hashes and all 39 results; normal Gradle commands above remain the repository-native verification path.

Changes to SMB ratios, absorption models, sensitivity, insulin-activity predictions, targets, or dosing frequency require physiological-model validation, representative retrospective replay, independently reviewed safety metrics, and appropriate clinical oversight. Relevant metrics include time in range, time below range, severe-low events, post-meal excursions, IOB excursions, and behavior across insulin types, sensors, and pumps. Passing software tests or producing a cleaner UI does not demonstrate greater clinical accuracy.
