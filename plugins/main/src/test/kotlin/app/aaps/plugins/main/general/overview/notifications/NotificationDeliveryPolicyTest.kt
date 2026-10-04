package app.aaps.plugins.main.general.overview.notifications

import app.aaps.core.interfaces.notifications.Notification
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationDeliveryPolicyTest {

    @Test
    fun `quiet mode suppresses routine confirmations but keeps them available in app`() {
        assertFalse(NotificationDeliveryPolicy.shouldPost(true, Notification.PROFILE_SET_OK, Notification.INFO, null))
        assertFalse(NotificationDeliveryPolicy.shouldPost(true, Notification.DYN_ISF_FALLBACK, Notification.INFO, null))
        assertFalse(NotificationDeliveryPolicy.shouldPost(true, Notification.NEW_VERSION_DETECTED, Notification.LOW, null))
    }

    @Test
    fun `normal and urgent messages always pass even for informational IDs`() {
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.USER_MESSAGE, Notification.URGENT, null))
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.PROFILE_SET_OK, Notification.NORMAL, null))
    }

    @Test
    fun `pump warnings and Bluetooth failures pass despite informational severity`() {
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.PUMP_WARNING, Notification.ANNOUNCEMENT, null))
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.BLUETOOTH_NOT_ENABLED, Notification.INFO, null))
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.TIME_OR_TIMEZONE_CHANGE, Notification.INFO, null))
    }

    @Test
    fun `connection reminders always pass`() {
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.PUMP_CONNECTION_REMINDER, Notification.INFO, null))
    }

    @Test
    fun `sound carrying and unknown messages are preserved`() {
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.USER_MESSAGE, Notification.INFO, 7))
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, 12345, Notification.ANNOUNCEMENT, null))
        assertTrue(NotificationDeliveryPolicy.shouldPost(true, Notification.USER_MESSAGE, -1, null))
    }

    @Test
    fun `turning quiet mode off restores ordinary notifications`() {
        assertTrue(NotificationDeliveryPolicy.shouldPost(false, Notification.PROFILE_SET_OK, Notification.INFO, null))
    }
}
