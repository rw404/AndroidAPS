package app.aaps.core.utils.fabric

import com.google.firebase.installations.FirebaseInstallations

object InstanceId {
    var instanceId : String = ""

    init {
        // A separate build may not have a Firebase project configured.
        runCatching { FirebaseInstallations.getInstance() }.getOrNull()?.id?.addOnCompleteListener {
            if (it.isSuccessful) instanceId = it.result
        }
    }
}
