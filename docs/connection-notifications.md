# Pump connection reminders

The new **Quiet pump connection reminders** setting is enabled by default under **Preferences → Local alerts**. It replaces the generic repeating **Pump unreachable** alarm with a silent reminder when pump data is at least 20, 40, or 60 minutes old. Disabling this setting restores the existing configurable pump-unreachable alarm.

Glucose alerts, hypoglycemia alerts, actual pump faults, uncertain insulin delivery alerts, and treatment commands retain their existing behavior. Quiet reminders do not generate the legacy pump-unreachable SMS or Nightscout therapy announcements. Those legacy side effects remain available when quiet connection mode is disabled.

## Delivery and timing

- Each stage appears once during a connection outage. A single notification is updated at 40 and 60 minutes, without sound, vibration, or a heads-up popup. Its Android channel uses low importance.
- A recently accepted pump-status request must follow the last successful pump communication. Rejected queue requests do not update the read-attempt timestamp. This prevents delayed keep-alive scheduling from presenting a connection failure without a recent communication attempt.
- If scheduling skips thresholds, only the current stage appears. No backlog of 20- and 40-minute reminders is replayed.
- Keep-alive runs approximately every five minutes, subject to Android scheduling. Reminders appear on the first eligible execution after a threshold, rather than at a guaranteed wall-clock second.
- Reminder stage and accepted recovery attempt are stored locally and excluded from settings export. Restarting the process or temporarily disabling the mode does not replay a stage.
- Intentional pump disconnection and client-only mode dismiss the reminder without treating the pump as recovered. Recovery requires a newer valid pump-data timestamp no more than 15 minutes old. Missing, future, or regressing timestamps cannot clear the reminder.

The existing setting for raising notifications as Android notifications continues to control whether a reminder is shown in the system tray. In-app reminders remain available.

## Recovery at 40 minutes

At or after the 40-minute threshold, AAPS requests one extra status read through the normal pump command queue. The request is permitted only when the pump is initialized and idle, no connection or handshake is in progress, and the queue has no pending or running command, bolus, or pending disconnect. A rejected request can be retried on a later eligible keep-alive execution. Once the queue accepts it, the extra request is recorded and will not be repeated for that outage.

This uses the pump driver's existing connection path. It does not switch the phone's Bluetooth adapter off and on, which modern Android versions restrict and which would also interrupt connected glucose sensors. Queue acceptance records an attempt, not successful recovery. Only new pump data clears the reminder and permits another one-shot recovery request in a later outage. An accepted extra request also prevents a duplicate ordinary keep-alive status request in the same execution.

## Validation

The final Android Gradle verification on October 4, 2026 passed all 33 targeted app tests, including the worker integration tests, reminder policy and recovery helper. Together with SMB and notification-delivery tests, 72 targeted tests passed. The debug APK was built and its signature verified. See the [final Android report](verification/README.md). Hardware and system-tray behavior still need device testing.

`PumpConnectionReminderPolicyTest` covers the exact 20/40/60-minute boundaries, deduplication after state restoration, delayed scheduling, fresh recovery, cached and invalid timestamps, clock rollback, disabled modes, and the one-shot recovery decision. `KeepAliveWorkerTest` adds integration coverage for persisted reminder and recovery state, quiet-mode replacement, legacy fallback, avoiding duplicate queued status reads, and excluding rejected ordinary requests from connection-failure evidence. `PumpConnectionRecoveryTest` covers each pump and command-queue guard.

The 16 policy tests and four recovery-helper tests passed using the actual Kotlin 2.2.0 compiler and JUnit Platform 6.0.1 in the [reproducible focused JVM harness](../tools/verification/README.md), alongside 39 other regression tests. The [verification manifest](verification/jvm-harness-2026-10-04.json) and [JUnit report](verification/jvm-harness-2026-10-04.xml) preserve that 59-test result. Android integration tests and system-tray behavior require the Android build and device environment; they are not established by the standalone policy test result.

Run the Android unit tests in an environment with the repository's required Android SDK and dependencies:

```sh
./gradlew :app:testFullDebugUnitTest --tests 'app.aaps.receivers.PumpConnectionReminderPolicyTest' --tests 'app.aaps.receivers.PumpConnectionRecoveryTest' --tests 'app.aaps.receivers.KeepAliveWorkerTest'
```

On a test device or simulator, confirm that one reminder updates through 20/40/60 minutes, dismisses after a successful status read, remains deduplicated after an app restart, and stays silent with the new low-importance channel. Verify that quiet mode off restores the configured legacy alarm and that critical glucose and pump alerts still follow their existing delivery paths.
