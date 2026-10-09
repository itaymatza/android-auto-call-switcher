package org.carcallrouter.companion.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.carcallrouter.companion.core.CallSafety

class CellularClassifier(
    context: Context,
) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    @SuppressLint("MissingPermission")
    fun rejection(call: Call): String? {
        val details = call.details ?: return "Call details unavailable"
        val scope =
            CallSafety.Evidence(
                simAccount = null,
                emergencyFlag = details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL),
                emergencyCallbackMode = details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE),
                externalOrSelfManaged =
                    details.hasProperty(Call.Details.PROPERTY_IS_EXTERNAL_CALL) ||
                        details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED),
                conference = details.hasProperty(Call.Details.PROPERTY_CONFERENCE) || call.children.isNotEmpty(),
                telephoneHandlePresent = false,
                numberIsEmergency = null,
            )
        CallSafety.scopeRejection(scope)?.let { return it }
        val sim =
            try {
                details.accountHandle?.let { telecom?.getPhoneAccount(it)?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) }
            } catch (_: RuntimeException) {
                null
            }
        if (sim != true) return CallSafety.rejection(scope.copy(simAccount = sim))
        val handle = details.handle
        val number =
            if (handle?.scheme == "tel") {
                PhoneNumberUtils.extractNetworkPortion(handle.schemeSpecificPart)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        // Neither the telephone number nor the handle is ever logged or persisted.
        val emergency =
            number?.let {
                try {
                    telephony?.isEmergencyNumber(it)
                } catch (_: RuntimeException) {
                    null
                }
            }
        return CallSafety.rejection(
            scope.copy(
                simAccount = sim,
                telephoneHandlePresent = number != null,
                numberIsEmergency = emergency,
            ),
        )
    }
}
