@file:Suppress("UNUSED_PARAMETER")

package android.telephony

class TelephonyManager {
    var queries = 0
    var emergency = false
    var failure: RuntimeException? = null
    var lastNumber: String? = null

    fun isEmergencyNumber(number: String): Boolean {
        queries++
        lastNumber = number
        failure?.let { throw it }
        return emergency
    }
}

/** Boundary fake: tests control extraction; this does not implement Android number parsing. */
object PhoneNumberUtils {
    var extracted: String? = "5550100"
    var queries = 0

    fun extractNetworkPortion(number: String): String? {
        queries++
        return extracted
    }
}
