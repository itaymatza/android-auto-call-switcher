package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.EndpointIdentity.Basis
import org.carcallrouter.companion.core.EndpointIdentity.Candidate
import org.carcallrouter.companion.core.EndpointIdentity.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointIdentityTest {
    private val target = Candidate("endpoint-a", "Car Hands-Free")
    private val other = Candidate("endpoint-b", "Projection Unit")

    private fun resolve(
        labels: Set<String>,
        candidates: List<Candidate> = listOf(target, other),
        connected: Boolean = true,
        count: Int = 2,
        others: Set<String> = setOf(other.label),
        known: Boolean = true,
    ) = EndpointIdentity.resolve(candidates, connected, count, labels, others, known)

    @Test fun uniqueCurrentNameMatchWinsWithMultipleConnectedDevices() {
        val result = resolve(setOf("  CAR  hands-free ")) as Resolution.Matched
        assertEquals(target, result.candidate)
        assertEquals(Basis.UNIQUE_LABEL, result.basis)
    }

    @Test fun duplicateNamesFailClosed() {
        assertTrue(resolve(setOf(target.label), listOf(target, Candidate("c", "car hands-free"))) is Resolution.Unavailable)
    }

    @Test fun disconnectedTargetFailsClosed() {
        assertTrue(resolve(setOf(target.label), listOf(target), false, 0) is Resolution.Unavailable)
    }

    @Test fun singleEndpointAndSingleHfpCanResolveOemRenamedEndpoint() {
        val result = resolve(setOf("Different current alias"), listOf(target), count = 1) as Resolution.Matched
        assertEquals(Basis.SINGLE_CONNECTED_HFP, result.basis)
    }

    @Test fun unknownNameWithMultipleEndpointsFailsClosed() {
        assertTrue(resolve(setOf("Unknown")) is Resolution.Unavailable)
    }

    @Test fun noTelecomEndpointFailsClosed() {
        assertTrue(resolve(setOf(target.label), emptyList(), count = 1) is Resolution.Unavailable)
    }

    @Test fun whitespaceAndCaseNormalizationDoesNotMatchSubstrings() {
        val exact = Candidate("exact", "  CAR\tHANDS-FREE  ")
        val prefix = Candidate("prefix", "Car Hands-Free Extra")
        assertEquals(exact, (resolve(setOf("car hands-free"), listOf(exact, prefix)) as Resolution.Matched).candidate)
    }

    @Test fun fallbackRequiresExactlyOneConnectedHfpDevice() {
        for (count in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            assertTrue(resolve(setOf("Renamed"), listOf(target), count = count) is Resolution.Unavailable)
        }
    }

    @Test fun matchingLabelStillRequiresTargetHfpConnection() {
        val result = resolve(setOf(target.label), listOf(target), false, 1) as Resolution.Unavailable
        assertEquals("Selected device is not connected for calls", result.reason)
    }

    @Test fun selectedNonCarDevicesResolveWithoutBrandOrRoleHeuristics() {
        for (label in listOf("Earbuds", "Work Headset", "Desk Speakerphone", "Vehicle Hands-Free", "אוזניות")) {
            val chosen = Candidate("chosen", label)
            val result = resolve(setOf(label), listOf(other, target, chosen), count = 3) as Resolution.Matched
            assertEquals(chosen, result.candidate)
            assertEquals(Basis.UNIQUE_LABEL, result.basis)
        }
    }

    @Test fun knownCarNameNeverOverridesTheChosenHeadset() {
        val headset = Candidate("headset", "My Headset")
        val car = Candidate("car", "BMW")
        assertEquals(headset, (resolve(setOf(headset.label), listOf(car, headset, other), count = 3) as Resolution.Matched).candidate)
    }

    @Test fun renamedDeviceUsesOnlyCurrentLabels() {
        val renamed = Candidate("chosen", "New headset name")
        val oldNameNowBelongsToAnotherDevice = Candidate("other", "Old name")
        val result =
            resolve(
                setOf("New headset name", "Headset alias"),
                listOf(renamed, oldNameNowBelongsToAnotherDevice),
                others = setOf("Old name"),
            )
        assertEquals(renamed, (result as Resolution.Matched).candidate)
    }

    @Test fun duplicateConnectedDeviceNamesFailEvenWithOneMatchingEndpoint() {
        assertTrue(resolve(setOf(target.label), others = setOf("  CAR HANDS-FREE  ")) is Resolution.Unavailable)
    }

    @Test fun deviceNameAndLocalAliasCanBothMatchCurrentEndpoint() {
        for (label in listOf("Factory headset name", "My alias")) {
            val endpoint = Candidate("headset", label)
            val result = resolve(setOf("Factory headset name", "My alias"), listOf(endpoint, other)) as Resolution.Matched
            assertEquals(endpoint, result.candidate)
        }
    }

    @Test fun staleOrIncompleteLabelEvidenceCannotMatchAnOtherwiseUniqueName() {
        assertTrue(resolve(setOf(target.label), known = false) is Resolution.Unavailable)
        assertTrue(resolve(emptySet()) is Resolution.Unavailable)
        assertTrue(resolve(setOf(" \t")) is Resolution.Unavailable)
    }

    @Test fun unknownLabelsAllowOnlyOneDeviceAndOneEndpointTopology() {
        assertEquals(
            Basis.SINGLE_CONNECTED_HFP,
            (resolve(emptySet(), listOf(target), count = 1, known = false) as Resolution.Matched).basis,
        )
        assertTrue(resolve(setOf(target.label), listOf(target), count = 2, known = false) is Resolution.Unavailable)
        assertTrue(resolve(setOf(target.label), count = 1, known = false) is Resolution.Unavailable)
    }
}
