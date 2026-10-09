package org.carcallrouter.companion.ui

/** Connection observations are a preflight check, never evidence of active call audio. */
enum class CallDeviceCompatibility {
    NO_TARGET,
    CHECKING,
    CLASSIC_CONNECTED,
    LE_CONNECTED,
    HEARING_AID_CONNECTED,
    LE_AND_HEARING_AID_CONNECTED,
    NOT_CONNECTED,
    UNKNOWN,
    ;

    val leObserved: Boolean get() = this == LE_CONNECTED || this == LE_AND_HEARING_AID_CONNECTED

    fun blocksAutomaticSetup(classicConnectionKnown: Boolean): Boolean =
        classicConnectionKnown && (leObserved || this == HEARING_AID_CONNECTED)

    companion object {
        fun resolve(
            target: String?,
            connections: Map<Int, Set<String>?>,
            classicProfile: Int,
            leProfile: Int,
            hearingAidProfile: Int,
        ): CallDeviceCompatibility {
            if (target == null) return NO_TARGET
            if (connections.isEmpty()) return CHECKING
            val address = target.uppercase()
            if (connections[classicProfile]?.contains(address) == true) return CLASSIC_CONNECTED
            val le = connections[leProfile]?.contains(address) == true
            val hearingAid = connections[hearingAidProfile]?.contains(address) == true
            if (le && hearingAid) return LE_AND_HEARING_AID_CONNECTED
            if (le) return LE_CONNECTED
            if (hearingAid) return HEARING_AID_CONNECTED
            if (setOf(classicProfile, leProfile, hearingAidProfile).any { connections[it] == null }) return UNKNOWN
            return NOT_CONNECTED
        }
    }
}
