package app.aaps.receivers

import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PumpConnectionRecoveryTest {

    private val reason = "Pump connection recovery"

    private fun idlePump() = mock<Pump>().also { whenever(it.isInitialized()).thenReturn(true) }

    private fun idleQueue() = mock<CommandQueue>().also { whenever(it.readStatus(reason, null)).thenReturn(true) }

    @Test
    fun `idle pump recovery uses the existing status command queue`() {
        val pump = idlePump()
        val queue = idleQueue()

        assertTrue(PumpConnectionRecovery.requestStatus(pump, queue, reason))

        verify(queue).readStatus(reason, null)
        verify(pump, never()).connect(any())
        verify(pump, never()).disconnect(any())
        verify(pump, never()).stopConnecting()
    }

    @Test
    fun `pump initialization connection handshake and busy states defer recovery`() {
        val blockers: List<(Pump) -> Unit> = listOf(
            { whenever(it.isInitialized()).thenReturn(false) },
            { whenever(it.isBusy()).thenReturn(true) },
            { whenever(it.isConnecting()).thenReturn(true) },
            { whenever(it.isHandshakeInProgress()).thenReturn(true) }
        )

        blockers.forEach { block ->
            val pump = idlePump()
            val queue = idleQueue()
            block(pump)

            assertFalse(PumpConnectionRecovery.requestStatus(pump, queue, reason))
            verify(queue, never()).readStatus(any(), anyOrNull())
        }
    }

    @Test
    fun `queued or executing commands including treatments and disconnect defer recovery`() {
        val blockers: List<(CommandQueue) -> Unit> = listOf(
            { whenever(it.waitingForDisconnect).thenReturn(true) },
            { queue ->
                val command = mock<Command>()
                whenever(queue.performing()).thenReturn(command)
            },
            { whenever(it.size()).thenReturn(1) },
            { whenever(it.bolusInQueue()).thenReturn(true) }
        )

        blockers.forEach { block ->
            val pump = idlePump()
            val queue = idleQueue()
            block(queue)

            assertFalse(PumpConnectionRecovery.requestStatus(pump, queue, reason))
            verify(queue, never()).readStatus(any(), anyOrNull())
        }
    }

    @Test
    fun `rejected status request is not reported as an accepted recovery attempt`() {
        val pump = idlePump()
        val queue = idleQueue()
        whenever(queue.readStatus(reason, null)).thenReturn(false)

        assertFalse(PumpConnectionRecovery.requestStatus(pump, queue, reason))
        verify(queue).readStatus(reason, null)
    }
}
