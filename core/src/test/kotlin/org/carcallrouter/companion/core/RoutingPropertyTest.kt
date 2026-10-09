package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import org.junit.Test
import kotlin.random.Random

class RoutingPropertyTest {
    @Test
    fun seededStateExplorationPreservesSafetyInvariants() {
        val random = Random(20260917)
        val terminal = setOf(Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)
        var transitions = 0

        repeat(5_000) { trace ->
            val manual = random.nextInt(5) == 0
            val initial = if (random.nextBoolean()) Route.COMPETING_DEVICE else Route.entries.random(random)
            val policy = RoutingPolicy().also { it.begin(0, initial, manual) }
            var now = 0L

            repeat(50) {
                now += random.nextInt(0, 181)
                val route =
                    when (random.nextInt(10)) {
                        in 0..4 -> Route.COMPETING_DEVICE
                        in 5..7 -> Route.TARGET
                        else -> Route.entries.random(random)
                    }
                if (random.nextInt(100) == 0) policy.suspend("explicit pause")
                if (random.nextInt(4) == 0) policy.observeRoute(route, now)

                fun evidence(): Boolean? =
                    when (random.nextInt(100)) {
                        0 -> false
                        in 1..4 -> null
                        else -> true
                    }
                val snapshot =
                    Snapshot(
                        now = now,
                        enabled = random.nextInt(100) > 1,
                        authorized = random.nextInt(100) > 1,
                        active = random.nextInt(100) > 1,
                        singleCall = random.nextInt(100) > 1,
                        safeCellularCall = random.nextInt(100) > 1,
                        callSafetyPending = random.nextInt(100) < 3,
                        projection = evidence(),
                        targetHfpConnected = evidence(),
                        targetHfpAudio = evidence(),
                        targetHfpSampleAt = now,
                        selectorRecoveryAvailable = evidence(),
                        targetAvailable = evidence(),
                        endpointRevision = trace.toLong() + 1,
                        route = route,
                    )
                val before = policy.phase
                val decision = policy.evaluate(snapshot)
                transitions++

                check(policy.requests <= 1)
                check(policy.selectorRecoveries <= 1)
                if (before in terminal) {
                    check(!decision.requestTarget)
                    check(!decision.restoreSelector)
                }
                if (decision.requestTarget) {
                    check(snapshot.authorized == true && snapshot.active && snapshot.singleCall)
                    check(snapshot.safeCellularCall)
                    check(!snapshot.callSafetyPending)
                    check(snapshot.targetHfpConnected == true && snapshot.targetAvailable == true)
                    check(snapshot.targetHfpAudio == false)
                    check(manual || (snapshot.enabled && snapshot.projection == true))
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
                decision.wakeAt?.let { check(it > snapshot.now) }
            }
        }

        check(transitions == 250_000)
    }
}
