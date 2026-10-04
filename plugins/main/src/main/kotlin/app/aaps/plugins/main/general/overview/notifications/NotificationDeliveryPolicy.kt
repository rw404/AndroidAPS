package app.aaps.plugins.main.general.overview.notifications

import app.aaps.core.interfaces.notifications.Notification

/** Only known informational messages may be kept out of the system tray. */
object NotificationDeliveryPolicy {

    private val informationalIds = setOf(
        Notification.PROFILE_SET_OK,
        Notification.NS_ANNOUNCEMENT,
        Notification.NEW_VERSION_DETECTED,
        Notification.INSIGHT_DATE_TIME_UPDATED,
        Notification.OMNIPOD_POD_ALERTS_UPDATED,
        Notification.SMB_FALLBACK,
        Notification.DYN_ISF_FALLBACK,
        Notification.USER_MESSAGE
    )

    fun shouldPost(quietMode: Boolean, id: Int, level: Int, soundId: Int?): Boolean {
        if (!quietMode || id == Notification.PUMP_CONNECTION_REMINDER) return true
        if (level != Notification.LOW && level != Notification.INFO && level != Notification.ANNOUNCEMENT) return true
        if (soundId != null && soundId != 0) return true
        return id !in informationalIds
    }
}
