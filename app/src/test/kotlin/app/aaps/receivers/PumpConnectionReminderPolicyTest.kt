package app.aaps.receivers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PumpConnectionReminderPolicyTest {

    private val policy = PumpConnectionReminderPolicy()
    private val minute = 60_000L
    private val connection = 1_000_000L

    private fun evaluate(
        ageMinutes: Int,
        state: PumpConnectionReminderPolicy.State = PumpConnectionReminderPolicy.State(),
        now: Long = connection + ageMinutes * minute,
        lastConnection: Long = connection,
        lastReadAttempt: Long = now - minute,
        enabled: Boolean = true,
        disconnected: Boolean = false,
        pumpMode: Boolean = true
    ) = policy.evaluate(state, now, lastConnection, lastReadAttempt, enabled, disconnected, pumpMode)

    @Test
    fun `each stage begins at its exact boundary`() {
        var state = PumpConnectionReminderPolicy.State()
        for (threshold in listOf(20, 40, 60)) {
            val before = evaluate(threshold, state, now = connection + threshold * minute - 1)
            assertNull(before.reminderMinutes)
            val atBoundary = evaluate(threshold, before.state)
            assertEquals(threshold, atBoundary.reminderMinutes)
            assertFalse(atBoundary.dismiss)
            state = atBoundary.state
        }
    }

    @Test
    fun `a stage is emitted once including after restoring persisted state`() {
        val first = evaluate(20)
        val restoredState = PumpConnectionReminderPolicy.State(first.state.lastConnection, first.state.lastReminderMinutes)
        for (age in 20 until 40) assertNull(evaluate(age, restoredState).reminderMinutes)
        assertEquals(40, evaluate(40, restoredState).reminderMinutes)
    }

    @Test
    fun `a late scheduler emits only the current stage`() {
        assertEquals(40, evaluate(45).reminderMinutes)
        assertEquals(60, evaluate(180).reminderMinutes)
        val lastStage = evaluate(60).state
        assertNull(evaluate(180, lastStage).reminderMinutes)
    }

    @Test
    fun `a recent successful read dismisses the reminder and restarts thresholds`() {
        val outage = evaluate(60).state
        val now = connection + 65 * minute
        val recoveredConnection = now - minute
        val recovery = evaluate(65, outage, lastConnection = recoveredConnection)
        assertTrue(recovery.dismiss)
        assertEquals(0, recovery.state.lastReminderMinutes)
        assertFalse(recovery.state.recoveryAttempted)
        assertEquals(recoveredConnection, recovery.state.lastConnection)
        assertNull(evaluate(65, recovery.state, lastConnection = recoveredConnection).reminderMinutes)
        assertEquals(20, evaluate(84, recovery.state, lastConnection = recoveredConnection).reminderMinutes)
    }

    @Test
    fun `an older cached read does not confirm recovery or replay a stage`() {
        val outage = evaluate(40).state
        val cachedConnection = connection + 10 * minute
        val result = evaluate(45, outage, lastConnection = cachedConnection)
        assertFalse(result.dismiss)
        assertNull(result.reminderMinutes)
        assertEquals(40, result.state.lastReminderMinutes)
    }

    @Test
    fun `regressing timestamps preserve the episode`() {
        val outage = evaluate(20).state
        val result = evaluate(40, outage, lastConnection = connection - minute)
        assertEquals(outage, result.state)
        assertNull(result.reminderMinutes)
        assertFalse(result.dismiss)
    }

    @Test
    fun `missing or future connection data cannot emit or dismiss`() {
        val outage = evaluate(20).state
        for (invalidConnection in listOf(0L, -1L, connection + 41 * minute, Long.MAX_VALUE)) {
            val result = evaluate(40, outage, lastConnection = invalidConnection)
            assertEquals(outage, result.state)
            assertNull(result.reminderMinutes)
            assertFalse(result.dismiss)
        }
    }

    @Test
    fun `clock rollback cannot masquerade as a fresh successful connection`() {
        val outage = evaluate(20).state
        val result = evaluate(5, outage)
        assertNull(result.reminderMinutes)
        assertFalse(result.dismiss)
        assertEquals(20, result.state.lastReminderMinutes)
    }

    @Test
    fun `an attempted status read is required after the last successful connection`() {
        for (attempt in listOf(0L, connection - minute, connection)) {
            assertNull(evaluate(20, lastReadAttempt = attempt).reminderMinutes)
        }
    }

    @Test
    fun `recent read attempt allows scheduling jitter but rejects stale attempts`() {
        val now = connection + 20 * minute
        assertEquals(20, evaluate(20, lastReadAttempt = now - 330_000L).reminderMinutes)
        assertNull(evaluate(20, lastReadAttempt = now - 330_001L).reminderMinutes)
        assertNull(evaluate(20, lastReadAttempt = now + 1L).reminderMinutes)
    }

    @Test
    fun `fresh recovery is independent of read attempt freshness`() {
        val outage = evaluate(40).state
        val recovery = evaluate(41, outage, lastConnection = connection + 40 * minute, lastReadAttempt = 0L)
        assertTrue(recovery.dismiss)
        assertEquals(0, recovery.state.lastReminderMinutes)
    }

    @Test
    fun `disabled reminders intentional disconnect and client mode dismiss but preserve deduplication`() {
        val outage = evaluate(20).state
        val decisions = listOf(
            evaluate(40, outage, enabled = false),
            evaluate(40, outage, disconnected = true),
            evaluate(40, outage, pumpMode = false)
        )
        decisions.forEach {
            assertTrue(it.dismiss)
            assertNull(it.reminderMinutes)
            assertEquals(outage, it.state)
        }
    }

    @Test
    fun `reenabling during the same outage does not replay the reminder`() {
        val outage = evaluate(40).state.copy(recoveryAttempted = true)
        val disabled = evaluate(45, outage, enabled = false)
        val enabled = evaluate(45, disabled.state)
        assertNull(enabled.reminderMinutes)
        assertFalse(enabled.requestRecovery)
    }

    @Test
    fun `recovery is requested at 40 minutes until an attempt is accepted`() {
        assertFalse(evaluate(20).requestRecovery)
        val atForty = evaluate(40)
        assertTrue(atForty.requestRecovery)
        assertTrue(evaluate(45, atForty.state).requestRecovery)
        val accepted = atForty.state.copy(recoveryAttempted = true)
        assertFalse(evaluate(45, accepted).requestRecovery)
        assertFalse(evaluate(60, accepted).requestRecovery)
    }

    @Test
    fun `fresh successful read allows a new recovery attempt in the next outage`() {
        val oldOutage = evaluate(40).state.copy(recoveryAttempted = true)
        val recovered = evaluate(41, oldOutage, lastConnection = connection + 40 * minute)
        assertFalse(recovered.state.recoveryAttempted)
        val nextOutage = evaluate(80, recovered.state, lastConnection = connection + 40 * minute)
        assertTrue(nextOutage.requestRecovery)
        assertEquals(40, nextOutage.reminderMinutes)
    }

    @Test
    fun `disabled mode without an existing reminder does not request dismissal`() {
        assertFalse(evaluate(40, enabled = false).dismiss)
    }
}
