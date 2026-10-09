import android.content.Context
import android.net.Uri
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.carcallrouter.companion.telecom.CellularClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CellularClassifierTest {
    private val context = Context()
    private val telecom = TelecomManager()
    private val telephony = TelephonyManager()
    private val call = Call(Call.Details())

    @Before
    fun setup() {
        context.services[TelecomManager::class.java] = telecom
        context.services[TelephonyManager::class.java] = telephony
        PhoneNumberUtils.extracted = "5550100"
        PhoneNumberUtils.queries = 0
    }

    private fun reject() = CellularClassifier(context).rejection(call)

    private fun assertNoProtectedQueries() {
        assertEquals(0, telecom.queries)
        assertEquals(0, telephony.queries)
        assertEquals(0, PhoneNumberUtils.queries)
    }

    @Test
    fun explicitUnsafePropertiesRejectBeforeFailingProtectedQueries() {
        telecom.failure = SecurityException("no access")
        telephony.failure = IllegalStateException("service unavailable")
        val cases =
            mapOf(
                Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL to "Network-identified emergency call",
                Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE to "Emergency callback mode",
                Call.Details.PROPERTY_SELF_MANAGED to "External or self-managed call",
                Call.Details.PROPERTY_IS_EXTERNAL_CALL to "External or self-managed call",
                Call.Details.PROPERTY_CONFERENCE to "Conference call",
            )
        cases.forEach { (property, expected) ->
            requireNotNull(call.details).properties = property
            assertEquals(expected, reject())
            assertNoProtectedQueries()
        }
    }

    @Test
    fun emergencyPrecedenceSurvivesConflictingUnsupportedProperties() {
        requireNotNull(call.details).properties = 31
        assertEquals("Network-identified emergency call", reject())
        requireNotNull(call.details).properties = 30
        assertEquals("Emergency callback mode", reject())
        assertNoProtectedQueries()
    }

    @Test
    fun conferenceChildrenRejectWithoutConferenceProperty() {
        call.children = listOf(Call(Call.Details()))
        assertEquals("Conference call", reject())
        assertNoProtectedQueries()
    }

    @Test
    fun missingDetailsRejectWithoutQueries() {
        call.details = null
        assertEquals("Call details unavailable", reject())
        assertNoProtectedQueries()
    }

    @Test
    fun ordinaryVerifiedSimCallRequiresBothProtectedChecks() {
        assertNull(reject())
        assertEquals(1, telecom.queries)
        assertEquals(1, telephony.queries)
        assertEquals(1, PhoneNumberUtils.queries)
        assertEquals("5550100", telephony.lastNumber)
    }

    @Test
    fun managedVoipAccountCannotPassJustBecauseHandleUsesTel() {
        telecom.account = PhoneAccount(0)
        assertEquals("SIM-backed phone account not verified", reject())
        assertEquals(1, telecom.queries)
        assertEquals(0, telephony.queries)
        assertEquals(0, PhoneNumberUtils.queries)
    }

    @Test
    fun missingAccountHandleCannotBorrowAnotherSimAccount() {
        requireNotNull(call.details).accountHandle = null
        assertEquals("SIM-backed phone account not verified", reject())
        assertNoProtectedQueries()
    }

    @Test
    fun missingAndFailingTelecomServiceFailClosedBeforeNumberAccess() {
        context.services.remove(TelecomManager::class.java)
        assertEquals("SIM-backed phone account not verified", reject())
        assertNoProtectedQueries()
        context.services[TelecomManager::class.java] = telecom
        telecom.failure = SecurityException("revoked")
        assertEquals("SIM-backed phone account not verified", reject())
        assertEquals(0, PhoneNumberUtils.queries)
        assertEquals(0, telephony.queries)
        telecom.failure = null
        telecom.account = null
        assertEquals("SIM-backed phone account not verified", reject())
    }

    @Test
    fun hiddenAndNonTelephoneHandlesCannotProveEmergencyStatus() {
        val expected = "Telephone handle hidden or unavailable; emergency status unknown"
        requireNotNull(call.details).handle = null
        assertEquals(expected, reject())
        requireNotNull(call.details).handle = Uri("sip", "person@example.invalid")
        assertEquals(expected, reject())
        assertEquals(0, telephony.queries)
        assertEquals(0, PhoneNumberUtils.queries)
    }

    @Test
    fun emptyExtractedNumberCannotAuthorizeRouting() {
        PhoneNumberUtils.extracted = " "
        assertEquals("Telephone handle hidden or unavailable; emergency status unknown", reject())
        PhoneNumberUtils.extracted = null
        assertEquals("Telephone handle hidden or unavailable; emergency status unknown", reject())
        assertEquals(0, telephony.queries)
    }

    @Test
    fun missingAndFailingEmergencyServiceFailClosed() {
        context.services.remove(TelephonyManager::class.java)
        assertEquals("Emergency-number classification unavailable", reject())
        assertEquals(0, telephony.queries)
        context.services[TelephonyManager::class.java] = telephony
        telephony.failure = IllegalStateException("unavailable")
        assertEquals("Emergency-number classification unavailable", reject())
    }

    @Test
    fun emergencyNumberRejectedWithoutNetworkFlag() {
        telephony.emergency = true
        assertEquals("Emergency number", reject())
    }

    @Test
    fun detailsChangesAreReclassifiedWithoutReusingPriorAcceptance() {
        val classifier = CellularClassifier(context)
        assertNull(classifier.rejection(call))
        requireNotNull(call.details).properties = Call.Details.PROPERTY_SELF_MANAGED
        assertEquals("External or self-managed call", classifier.rejection(call))
        assertEquals(1, telecom.queries)
        assertEquals(1, telephony.queries)
        call.details = Call.Details()
        telephony.emergency = true
        assertEquals("Emergency number", classifier.rejection(call))
    }
}
