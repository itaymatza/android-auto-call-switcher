package org.carcallrouter.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDeviceCompatibilityTest {
    private val classic = 1
    private val le = 22
    private val hearingAid = 21
    private val target = "AA:BB:CC:DD:EE:FF"
    private val other = "11:22:33:44:55:66"

    private fun resolve(
        target: String?,
        profiles: Map<Int, Set<String>?>,
    ) = CallDeviceCompatibility.resolve(target, profiles, classic, le, hearingAid)

    @Test fun noSelectionAndPendingInspectionAreDistinct() {
        assertEquals(CallDeviceCompatibility.NO_TARGET, resolve(null, emptyMap()))
        assertEquals(CallDeviceCompatibility.CHECKING, resolve(target, emptyMap()))
    }

    @Test fun classicWinsForDualTransportWithoutRequiringOtherProfileAccess() {
        assertEquals(CallDeviceCompatibility.CLASSIC_CONNECTED, resolve(target.lowercase(), mapOf(classic to setOf(target))))
        assertEquals(CallDeviceCompatibility.CLASSIC_CONNECTED, resolve(target, mapOf(classic to setOf(target), le to setOf(target))))
    }

    @Test fun leAndHearingAidConnectionsNeverBecomeClassicEvidence() {
        for (profile in setOf(le, hearingAid)) {
            assertEquals(
                if (profile == le) CallDeviceCompatibility.LE_CONNECTED else CallDeviceCompatibility.HEARING_AID_CONNECTED,
                resolve(target, mapOf(classic to emptySet(), profile to setOf(target))),
            )
        }
    }

    @Test fun unknownClassicDoesNotHideObservedOtherTransport() {
        assertEquals(CallDeviceCompatibility.LE_CONNECTED, resolve(target, mapOf(classic to null, le to setOf(target))))
    }

    @Test fun combinedOtherTransportsRemainDistinctAndNeverOverrideClassic() {
        val otherTransports = mapOf(classic to emptySet(), le to setOf(target), hearingAid to setOf(target))
        assertEquals(CallDeviceCompatibility.LE_AND_HEARING_AID_CONNECTED, resolve(target, otherTransports))
        assertEquals(CallDeviceCompatibility.CLASSIC_CONNECTED, resolve(target, otherTransports + (classic to setOf(target))))
    }

    @Test fun incompleteQueriesNeverDeclareTargetDisconnected() {
        assertEquals(CallDeviceCompatibility.UNKNOWN, resolve(target, mapOf(classic to emptySet())))
        assertEquals(CallDeviceCompatibility.UNKNOWN, resolve(target, mapOf(classic to null, le to emptySet(), hearingAid to emptySet())))
    }

    @Test fun peersDoNotEstablishTargetCompatibility() {
        assertEquals(
            CallDeviceCompatibility.NOT_CONNECTED,
            resolve(target, mapOf(classic to setOf(other), le to setOf(other), hearingAid to emptySet())),
        )
    }

    @Test fun onlyObservedUnsupportedTransportWithKnownClassicAbsenceBlocksSetup() {
        for (state in CallDeviceCompatibility.entries) {
            assertFalse(state.blocksAutomaticSetup(false))
            assertEquals(state.leObserved || state == CallDeviceCompatibility.HEARING_AID_CONNECTED, state.blocksAutomaticSetup(true))
        }
        assertTrue(resolve(target, mapOf(classic to emptySet(), le to setOf(target))).blocksAutomaticSetup(true))
        assertFalse(resolve(target, mapOf(classic to setOf(target), le to setOf(target))).blocksAutomaticSetup(true))
    }
}
