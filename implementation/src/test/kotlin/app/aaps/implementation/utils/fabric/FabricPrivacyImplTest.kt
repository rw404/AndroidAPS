package app.aaps.implementation.utils.fabric

import android.content.SharedPreferences
import android.os.Bundle
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.BooleanKey
import com.google.firebase.FirebaseApp
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

class FabricPrivacyImplTest {

    @Test
    fun unconfiguredFirebaseDoesNotBreakStartupRegardlessOfTelemetryPreference() {
        // Use the real Firebase singleton without a provider or Google client configuration.
        assertThrows(IllegalStateException::class.java) { FirebaseApp.getInstance() }
        for (optedIn in listOf(true, false)) {
            val preferences: SharedPreferences = mock()
            whenever(preferences.getBoolean(BooleanKey.MaintenanceEnableFabric.key, true)).thenReturn(optedIn)
            val privacy = assertDoesNotThrow<FabricPrivacyImpl> { FabricPrivacyImpl(mock(), preferences) }
            // MainActivity uses this to guard its direct Crashlytics metadata calls.
            assertFalse(privacy.fabricEnabled())
        }
    }

    @Test
    fun reportingCallsPreserveLocalErrorLoggingWithoutFirebase() {
        val logger: AAPSLogger = mock()
        val privacy = FabricPrivacyImpl(logger, mock())
        val error = IllegalStateException("Example local error")
        val serializedError = ByteArrayOutputStream().also { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(error) }
        }.toByteArray()

        assertDoesNotThrow {
            privacy.setUserProperty("App", "Healfi")
            privacy.logCustom("screen", Bundle())
            privacy.logCustom(Bundle())
            privacy.logCustom("startup")
            privacy.logMessage("local diagnostic")
            privacy.logException(error)
            privacy.logWearException(
                EventData.WearException(
                    timeStamp = 1L,
                    exception = serializedError,
                    board = "board",
                    fingerprint = "fingerprint",
                    sdk = "35",
                    model = "watch",
                    manufacturer = "manufacturer",
                    product = "product"
                )
            )
        }

        verify(logger).info(LTag.CORE, "Crashlytics log message: local diagnostic")
        verify(logger).error("Crashlytics log exception: ", error)
    }
}
