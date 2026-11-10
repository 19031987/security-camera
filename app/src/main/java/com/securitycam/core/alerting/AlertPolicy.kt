package com.securitycam.core.alerting

import com.securitycam.core.config.SecuritySettings

/**
 * Decides whether a recorded event should also notify people.
 * Every event is recorded either way; this only governs SMS, email, uploads and other alerts.
 */
object AlertPolicy {

    const val MODE_AWAY = "AWAY"
    const val MODE_HOME = "HOME"

    const val KIND_PERSON = "PERSON"
    const val KIND_ANIMAL = "ANIMAL"
    const val KIND_VEHICLE = "VEHICLE"
    const val KIND_OTHER = "OTHER"

    enum class Verdict {
        ALERT,

        /** Home mode: somebody is in, so events are recorded quietly. */
        QUIET_HOME_MODE,

        /** The owner has not asked to be notified about this kind of movement. */
        QUIET_NOT_SELECTED
    }

    /**
     * @param detectedKind what the on-device model saw, or null if the model could not run.
     * @param isLowLight true when the picture was taken in the dark.
     *
     * Two cases alert even though nothing selected was recognised, because staying silent
     * would be the unsafe mistake:
     *  - the model failed to run at all;
     *  - it is dark and the model recognised nothing, since it may simply not be able to see.
     */
    fun decide(settings: SecuritySettings, detectedKind: String?, isLowLight: Boolean): Verdict {
        if (settings.alertMode == MODE_HOME) return Verdict.QUIET_HOME_MODE

        val selected = when (detectedKind) {
            null -> true
            KIND_PERSON -> settings.notifyPerson
            KIND_ANIMAL -> settings.notifyAnimal
            KIND_VEHICLE -> settings.notifyVehicle
            else -> settings.notifyOther || isLowLight
        }
        return if (selected) Verdict.ALERT else Verdict.QUIET_NOT_SELECTED
    }
}
