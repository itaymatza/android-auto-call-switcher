package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioStatusTest {
    @Test fun onlyFreshConsistentEvidenceCanProduceAPositiveAudioStatus() {
        var cases = 0
        for (hasCall in listOf(false, true)) {
            for (active in listOf(false, true)) {
                for (reported in listOf(false, true)) {
                    for (fresh in listOf(false, true)) {
                        for (owners in 0..3) {
                            for (target in listOf(null, false, true)) {
                                for (route in RoutingPolicy.Route.entries) {
                                    val result = CallAudioStatus.assess(hasCall, active, reported, fresh, owners, target, route)
                                    if (result == CallAudioStatus.TARGET_OBSERVED) {
                                        assertTrue(hasCall && active && !reported && fresh && owners == 1 && target == true)
                                        assertEquals(RoutingPolicy.Route.TARGET, route)
                                    }
                                    if (hasCall && reported) assertEquals(CallAudioStatus.USER_REPORTED_PROBLEM, result)
                                    if (hasCall && active && !reported && (!fresh || target == null || owners > 1)) {
                                        assertEquals(CallAudioStatus.UNCONFIRMED, result)
                                    }
                                    cases++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(1536, cases)
    }

    @Test fun reportedEndpointIsNeverSubstitutedForAudioEvidence() {
        assertEquals(
            CallAudioStatus.MISMATCH,
            CallAudioStatus.assess(true, true, false, true, 1, false, RoutingPolicy.Route.TARGET),
        )
        assertEquals(
            CallAudioStatus.NO_BLUETOOTH_AUDIO,
            CallAudioStatus.assess(true, true, false, true, 0, false, RoutingPolicy.Route.TARGET),
        )
        assertEquals(
            CallAudioStatus.UNCONFIRMED,
            CallAudioStatus.assess(true, true, false, false, 1, true, RoutingPolicy.Route.TARGET),
        )
    }
}
