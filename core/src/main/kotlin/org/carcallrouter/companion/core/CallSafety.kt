package org.carcallrouter.companion.core

/** Fail closed. A hidden telephone handle cannot be proven non-emergency in this version. */
object CallSafety {
    data class Evidence(
        val simAccount: Boolean?,
        val emergencyFlag: Boolean,
        val emergencyCallbackMode: Boolean,
        val externalOrSelfManaged: Boolean,
        val conference: Boolean,
        val telephoneHandlePresent: Boolean,
        val numberIsEmergency: Boolean?,
    )

    /** Explicit unsupported scope is decided before protected account/number lookups. */
    fun scopeRejection(e: Evidence): String? =
        when {
            e.emergencyFlag -> "Network-identified emergency call"
            e.emergencyCallbackMode -> "Emergency callback mode"
            e.externalOrSelfManaged -> "External or self-managed call"
            e.conference -> "Conference call"
            else -> null
        }

    fun rejection(e: Evidence): String? =
        scopeRejection(e) ?: when {
            e.simAccount != true -> "SIM-backed phone account not verified"
            !e.telephoneHandlePresent -> "Telephone handle hidden or unavailable; emergency status unknown"
            e.numberIsEmergency == null -> "Emergency-number classification unavailable"
            e.numberIsEmergency -> "Emergency number"
            else -> null
        }
}
