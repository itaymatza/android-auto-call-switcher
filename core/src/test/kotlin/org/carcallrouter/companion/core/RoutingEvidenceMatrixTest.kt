package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingEvidenceMatrixTest {
    @Test
    fun automaticRoutingRequestsOnlyWithCompletePositiveEvidence() {
        var combinations = 0
        var requests = 0
        for (enabled in booleans) {
            for (authorized in booleans) {
                for (active in booleans) {
                    for (singleCall in booleans) {
                        for (safe in booleans) {
                            for (projection in nullableBooleans) {
                                for (hfp in nullableBooleans) {
                                    for (available in nullableBooleans) {
                                        for (audio in nullableBooleans) {
                                            for (route in Route.entries) {
                                                val policy = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, route) }
                                                val decision =
                                                    policy.evaluate(
                                                        snapshot(
                                                            enabled,
                                                            authorized,
                                                            active,
                                                            singleCall,
                                                            safe,
                                                            projection,
                                                            hfp,
                                                            audio,
                                                            available,
                                                            route,
                                                        ),
                                                    )
                                                val expected =
                                                    enabled &&
                                                        authorized &&
                                                        active &&
                                                        singleCall &&
                                                        safe &&
                                                        projection == true &&
                                                        hfp == true &&
                                                        available == true &&
                                                        audio == false
                                                val label =
                                                    snapshotLabel(
                                                        enabled,
                                                        authorized,
                                                        active,
                                                        singleCall,
                                                        safe,
                                                        projection,
                                                        hfp,
                                                        audio,
                                                        available,
                                                        route,
                                                    )
                                                assertEquals(
                                                    "Unexpected decision for $label",
                                                    expected,
                                                    decision.requestTarget,
                                                )
                                                combinations++
                                                if (decision.requestTarget) requests++
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(20_736, combinations)
        assertEquals(8, requests)
    }

    @Test
    fun manualRoutingBypassesOnlyToggleAndProjectionAcrossTheFullMatrix() {
        var combinations = 0
        for (enabled in booleans) {
            for (authorized in booleans) {
                for (active in booleans) {
                    for (singleCall in booleans) {
                        for (safe in booleans) {
                            for (projection in nullableBooleans) {
                                for (hfp in nullableBooleans) {
                                    for (available in nullableBooleans) {
                                        for (audio in nullableBooleans) {
                                            val route = Route.COMPETING_DEVICE
                                            val policy =
                                                RoutingPolicy(settleDelayMs = 0).also {
                                                    it.begin(0, route, manualOneShot = true)
                                                }
                                            val decision =
                                                policy.evaluate(
                                                    snapshot(
                                                        enabled,
                                                        authorized,
                                                        active,
                                                        singleCall,
                                                        safe,
                                                        projection,
                                                        hfp,
                                                        audio,
                                                        available,
                                                        route,
                                                    ),
                                                )
                                            val expected =
                                                authorized &&
                                                    active &&
                                                    singleCall &&
                                                    safe &&
                                                    hfp == true &&
                                                    available == true &&
                                                    audio == false
                                            assertEquals(expected, decision.requestTarget)
                                            combinations++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(2_592, combinations)
    }

    private fun snapshot(
        enabled: Boolean,
        authorized: Boolean,
        active: Boolean,
        singleCall: Boolean,
        safe: Boolean,
        projection: Boolean?,
        hfp: Boolean?,
        audio: Boolean?,
        available: Boolean?,
        route: Route,
    ) = Snapshot(0, enabled, authorized, active, singleCall, safe, projection, hfp, audio, 0, false, available, 1, route)

    private fun snapshotLabel(
        enabled: Boolean,
        authorized: Boolean,
        active: Boolean,
        singleCall: Boolean,
        safe: Boolean,
        projection: Boolean?,
        hfp: Boolean?,
        audio: Boolean?,
        available: Boolean?,
        route: Route,
    ) = "enabled=$enabled authorized=$authorized active=$active single=$singleCall safe=$safe " +
        "projection=$projection hfp=$hfp audio=$audio available=$available route=$route"

    private companion object {
        val booleans = listOf(false, true)
        val nullableBooleans = listOf<Boolean?>(false, null, true)
    }
}
