package org.carcallrouter.companion.ui

enum class SetupPhase {
    NEEDS_RUNTIME_PERMISSIONS,
    NEEDS_TELECOM_AUTHORIZATION,
    NEEDS_TARGET_DEVICE,
    UNSUPPORTED_CALL_TRANSPORT,
    READY,
    ACTIVE,
}

data class SetupState(
    val runtimePermissionsGranted: Boolean,
    val telecomAuthorized: Boolean,
    val targetSelected: Boolean,
    val automationEnabled: Boolean,
    val unsupportedCallTransport: Boolean = false,
) {
    val completedSteps: Int =
        listOf(
            runtimePermissionsGranted,
            telecomAuthorized,
            targetSelected,
        ).count { it }

    val ready: Boolean = completedSteps == REQUIRED_STEPS && !unsupportedCallTransport

    val phase: SetupPhase =
        when {
            !runtimePermissionsGranted -> SetupPhase.NEEDS_RUNTIME_PERMISSIONS
            !targetSelected -> SetupPhase.NEEDS_TARGET_DEVICE
            unsupportedCallTransport -> SetupPhase.UNSUPPORTED_CALL_TRANSPORT
            !telecomAuthorized -> SetupPhase.NEEDS_TELECOM_AUTHORIZATION
            automationEnabled -> SetupPhase.ACTIVE
            else -> SetupPhase.READY
        }

    companion object {
        const val REQUIRED_STEPS = 3
    }
}
