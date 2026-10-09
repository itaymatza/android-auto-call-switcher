@file:Suppress("UNUSED_PARAMETER")

package android.telecom

import android.net.Uri

class Call(
    var details: Details?,
) {
    var children: List<Call> = emptyList()

    class Details {
        var properties = 0
        var accountHandle: PhoneAccountHandle? = PhoneAccountHandle("sim")
        var handle: Uri? = Uri("tel", "5550100")

        fun hasProperty(property: Int) = properties and property != 0

        companion object {
            const val PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL = 1
            const val PROPERTY_EMERGENCY_CALLBACK_MODE = 2
            const val PROPERTY_IS_EXTERNAL_CALL = 4
            const val PROPERTY_SELF_MANAGED = 8
            const val PROPERTY_CONFERENCE = 16
        }
    }
}

data class PhoneAccountHandle(
    val id: String,
)

class PhoneAccount(
    private val capabilities: Int,
) {
    fun hasCapabilities(capability: Int) = capabilities and capability == capability

    companion object {
        const val CAPABILITY_SIM_SUBSCRIPTION = 4
    }
}

class TelecomManager {
    var authorized = true
    var authorizationQueries = 0
    var authorizationFailure: RuntimeException? = null

    fun hasManageOngoingCallsPermission(): Boolean {
        authorizationQueries++
        authorizationFailure?.let { throw it }
        return authorized
    }

    var queries = 0
    var account: PhoneAccount? = PhoneAccount(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
    var failure: RuntimeException? = null

    fun getPhoneAccount(handle: PhoneAccountHandle): PhoneAccount? {
        queries++
        failure?.let { throw it }
        return account
    }
}
