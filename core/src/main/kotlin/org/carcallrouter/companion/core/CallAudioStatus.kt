package org.carcallrouter.companion.core

/** Current sampled evidence, independent of setup, request acceptance, or earlier success. */
enum class CallAudioStatus {
    NO_CALL,
    WAITING_FOR_ANSWER,
    UNCONFIRMED,
    TARGET_OBSERVED,
    MISMATCH,
    OTHER_DEVICE,
    NO_BLUETOOTH_AUDIO,
    USER_REPORTED_PROBLEM,
    ;

    companion object {
        fun assess(
            hasCall: Boolean,
            active: Boolean,
            userReportedProblem: Boolean,
            fresh: Boolean,
            ownerCount: Int,
            targetAudio: Boolean?,
            route: RoutingPolicy.Route,
        ): CallAudioStatus =
            when {
                !hasCall -> NO_CALL
                userReportedProblem -> USER_REPORTED_PROBLEM
                !active -> WAITING_FOR_ANSWER
                !fresh || targetAudio == null || ownerCount > 1 -> UNCONFIRMED
                ownerCount == 0 -> NO_BLUETOOTH_AUDIO
                targetAudio && route == RoutingPolicy.Route.TARGET -> TARGET_OBSERVED
                route == RoutingPolicy.Route.UNKNOWN -> UNCONFIRMED
                targetAudio || route == RoutingPolicy.Route.TARGET -> MISMATCH
                else -> OTHER_DEVICE
            }
    }
}
