package app.aaps.receivers

/**
 * Quiet connection reminders are independent of pump, glucose and delivery alarms.
 * A recent status request is required so a delayed keep-alive cannot invent a connection failure.
 */
class PumpConnectionReminderPolicy {

    data class State(val lastConnection: Long = 0L, val lastReminderMinutes: Int = 0, val recoveryAttempted: Boolean = false)

    data class Decision(val state: State, val reminderMinutes: Int? = null, val dismiss: Boolean = false, val requestRecovery: Boolean = false)

    fun evaluate(
        state: State,
        now: Long,
        lastConnection: Long,
        lastReadAttempt: Long,
        enabled: Boolean,
        disconnected: Boolean,
        pumpMode: Boolean
    ): Decision {
        if (!enabled || disconnected || !pumpMode)
            return Decision(state, dismiss = state.lastReminderMinutes > 0)

        // Missing, future or regressing data cannot confirm recovery or start a new episode.
        if (now <= 0L || lastConnection <= 0L || lastConnection > now || lastConnection < state.lastConnection)
            return Decision(state)

        val connectionAge = now - lastConnection
        val connectionAdvanced = lastConnection > state.lastConnection
        val observedState = state.copy(lastConnection = lastConnection)
        if (connectionAdvanced && connectionAge <= FRESH_CONNECTION_MILLIS)
            return Decision(State(lastConnection), dismiss = state.lastReminderMinutes > 0)

        // The request must follow the last successful read; opening Bluetooth alone is not recovery.
        if (lastReadAttempt <= lastConnection || lastReadAttempt > now || now - lastReadAttempt > RECENT_ATTEMPT_MILLIS)
            return Decision(observedState)

        // If scheduling skipped a threshold, show only the current stage, never a burst of old reminders.
        val minutes = THRESHOLDS.lastOrNull { connectionAge >= it * MINUTE_MILLIS } ?: return Decision(observedState)
        val requestRecovery = minutes >= 40 && !state.recoveryAttempted
        if (minutes <= state.lastReminderMinutes) return Decision(observedState, requestRecovery = requestRecovery)

        return Decision(observedState.copy(lastReminderMinutes = minutes), reminderMinutes = minutes, requestRecovery = requestRecovery)
    }

    companion object {

        private const val MINUTE_MILLIS = 60_000L
        private const val FRESH_CONNECTION_MILLIS = 15 * MINUTE_MILLIS
        private const val RECENT_ATTEMPT_MILLIS = 5 * MINUTE_MILLIS + 30_000L
        private val THRESHOLDS = listOf(20, 40, 60)
    }
}
