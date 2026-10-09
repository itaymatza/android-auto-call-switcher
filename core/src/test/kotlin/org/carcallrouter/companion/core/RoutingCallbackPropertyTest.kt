package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.RequestError
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class RoutingCallbackPropertyTest {
    @Test
    fun seededCallbacksAndEvidenceChurnPreserveRequestInvariants() {
        val random = Random(20260921)
        val terminal = setOf(Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)
        var transitions = 0

        repeat(15_000) { session ->
            val manual = random.nextInt(7) == 0
            val initial = Route.entries.random(random)
            val policy = RoutingPolicy().also { it.begin(0, initial, manual) }
            var now = 0L

            repeat(50) {
                now += random.nextLong(0, 251)
                val route = Route.entries.random(random)
                if (random.nextInt(8) == 0) policy.observeRoute(route, now)
                if (random.nextInt(500) == 0) policy.suspend("seeded external stop")

                val snapshot =
                    Snapshot(
                        now = now,
                        enabled = random.nextInt(100) > 2,
                        authorized = random.nextInt(100) > 2,
                        active = random.nextInt(100) > 2,
                        singleCall = random.nextInt(100) > 2,
                        safeCellularCall = random.nextInt(100) > 2,
                        callSafetyPending = random.nextInt(100) < 3,
                        projection = evidence(random),
                        targetHfpConnected = evidence(random),
                        targetHfpAudio = evidence(random),
                        targetHfpSampleAt = now,
                        selectorRecoveryAvailable = evidence(random),
                        targetAvailable = evidence(random),
                        endpointRevision = session.toLong() * 100 + it,
                        route = route,
                    )
                val before = policy.phase
                val decision = policy.evaluate(snapshot)
                transitions++

                if (before in terminal) {
                    assertEquals(false, decision.requestTarget)
                    assertEquals(false, decision.restoreSelector)
                }
                check(policy.requests <= 1)
                check(policy.selectorRecoveries <= 1)
                decision.wakeAt?.let { wake -> check(wake > now) }
                if (decision.requestTarget) {
                    check(snapshot.authorized == true && snapshot.active && snapshot.singleCall)
                    check(snapshot.safeCellularCall)
                    check(!snapshot.callSafetyPending)
                    check(snapshot.targetHfpConnected == true && snapshot.targetAvailable == true)
                    check(snapshot.targetHfpAudio == false)
                    check(manual || (snapshot.enabled && snapshot.projection == true))

                    val attempt = requireNotNull(decision.requestAttempt)
                    when (random.nextInt(7)) {
                        0 -> policy.requestSucceeded(attempt, now)
                        1 -> policy.requestFailed(attempt, RequestError.TIMEOUT)
                        2 -> policy.requestFailed(attempt, RequestError.ENDPOINT_GONE)
                        3 -> policy.requestFailed(attempt, RequestError.CANCELLED_BY_OTHER)
                        4 -> policy.requestFailed(attempt, RequestError.UNSPECIFIED)
                        5 -> policy.requestFailed(attempt + 1, RequestError.RUNTIME_EXCEPTION)
                    }
                }
                if (decision.restoreSelector) {
                    check(!decision.requestTarget)
                    check(decision.requestAttempt == null)
                    check(snapshot.route == Route.TARGET)
                    check(snapshot.targetHfpConnected == true)
                    check(snapshot.targetAvailable == true)
                    check(snapshot.targetHfpAudio == false)
                    check(snapshot.selectorRecoveryAvailable == true)
                    check(policy.requests == 1)
                    check(policy.selectorRecoveries == 1)
                    check(policy.phase == Phase.RECOVERING_SELECTOR)
                }
            }
        }
        assertEquals(750_000, transitions)
    }

    private fun evidence(random: Random): Boolean? =
        when (random.nextInt(20)) {
            0 -> false
            1 -> null
            else -> true
        }
}
