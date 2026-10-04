package app.aaps.receivers

import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.queue.CommandQueue

/**
 * Ask the normal command queue to reconnect and read status when the pump and queue are idle.
 * This does not toggle the phone's Bluetooth adapter or issue a treatment command.
 * A queued request is an attempt; only newer pump data confirms that communication recovered.
 */
object PumpConnectionRecovery {

    fun requestStatus(pump: Pump, commandQueue: CommandQueue, reason: String): Boolean {
        if (!pump.isInitialized() || pump.isBusy() || pump.isConnecting() || pump.isHandshakeInProgress()) return false
        if (commandQueue.waitingForDisconnect || commandQueue.performing() != null || commandQueue.size() != 0 || commandQueue.bolusInQueue()) return false

        return commandQueue.readStatus(reason, null)
    }
}
