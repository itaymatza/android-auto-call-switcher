package org.carcallrouter.companion.ui

/** Connection observations are a preflight check, never evidence of active call audio. */
enum class CallDeviceCompatibility {
    NO_TARGET,
    CHECKING,
    CLASSIC_CONNECTED,
    OTHER_TRANSPORT_CONNECTED,
    NOT_CONNECTED,
    UNKNOWN,
    ;

    companion object {
        fun resolve(
            target: String?,
            connections: Map<Int, Set<String>?>,
            classicProfile: Int,
            otherProfiles: Set<Int>,
        ): CallDeviceCompatibility {
            if (target == null) return NO_TARGET
            if (connections.isEmpty()) return CHECKING
            val address = target.uppercase()
            if (connections[classicProfile]?.contains(address) == true) return CLASSIC_CONNECTED
            if (otherProfiles.any { connections[it]?.contains(address) == true }) return OTHER_TRANSPORT_CONNECTED
            if ((otherProfiles + classicProfile).any { connections[it] == null }) return UNKNOWN
            return NOT_CONNECTED
        }
    }
}
