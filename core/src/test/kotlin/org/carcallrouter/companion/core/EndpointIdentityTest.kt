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

    @Test fun uniqueNameMatchWinsWithMultipleConnectedDevices() {
        val result = EndpointIdentity.resolve("  CAR  hands-free ", listOf(target, other), true, 2)
        assertTrue(result is Resolution.Matched)
        result as Resolution.Matched
        assertEquals(target, result.candidate)
        assertEquals(Basis.UNIQUE_LABEL, result.basis)
    }

    @Test fun duplicateNamesFailClosed() {
        val result =
            EndpointIdentity.resolve(
                "Car Hands-Free",
                listOf(target, Candidate("endpoint-c", "car hands-free")),
                true,
                2,
            )
        assertTrue(result is Resolution.Unavailable)
    }

    @Test fun disconnectedTargetFailsClosed() {
        assertTrue(EndpointIdentity.resolve(target.label, listOf(target), false, 0) is Resolution.Unavailable)
    }

    @Test fun singleEndpointAndSingleHfpCanResolveOemRenamedEndpoint() {
        val result = EndpointIdentity.resolve("Saved alias", listOf(target), true, 1)
        assertTrue(result is Resolution.Matched)
        assertEquals(Basis.SINGLE_CONNECTED_HFP, (result as Resolution.Matched).basis)
    }

    @Test fun unknownNameWithMultipleEndpointsFailsClosed() {
        assertTrue(
            EndpointIdentity.resolve("Unknown", listOf(target, other), true, 2) is Resolution.Unavailable,
        )
    }

    @Test fun noTelecomEndpointFailsClosed() {
        assertTrue(EndpointIdentity.resolve(target.label, emptyList(), true, 1) is Resolution.Unavailable)
    }

    @Test fun whitespaceAndCaseNormalizationDoesNotMatchSubstrings() {
        val exact = Candidate("exact", "  CAR\tHANDS-FREE  ")
        val prefix = Candidate("prefix", "Car Hands-Free Extra")
        val result = EndpointIdentity.resolve("car hands-free", listOf(exact, prefix), true, 2)
        assertEquals(exact, (result as Resolution.Matched).candidate)
    }

    @Test fun fallbackRequiresExactlyOneConnectedHfpDevice() {
        for (count in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            assertTrue(
                EndpointIdentity.resolve("Renamed", listOf(target), true, count) is Resolution.Unavailable,
            )
        }
    }

    @Test fun matchingLabelStillRequiresTargetHfpConnection() {
        val result = EndpointIdentity.resolve(target.label, listOf(target), false, 1)
        assertEquals(
            "Selected device is not connected for calls",
            (result as Resolution.Unavailable).reason,
        )
    }

    @Test fun selectedNonCarDevicesResolveWithoutBrandOrRoleHeuristics() {
        for (label in listOf("Earbuds", "Work Headset", "Desk Speakerphone", "Vehicle Hands-Free", "אוזניות")) {
            val chosen = Candidate("chosen", label)
            val candidates = listOf(other, target, chosen)
            val result = EndpointIdentity.resolve(label, candidates, true, 3) as Resolution.Matched
            assertEquals(chosen, result.candidate)
            assertEquals(Basis.UNIQUE_LABEL, result.basis)
        }
    }

    @Test fun knownCarNameNeverOverridesTheChosenHeadset() {
        val headset = Candidate("headset", "My Headset")
        val car = Candidate("car", "BMW")
        val result = EndpointIdentity.resolve(headset.label, listOf(car, headset, other), true, 3)
        assertEquals(headset, (result as Resolution.Matched).candidate)
    }

    @Test fun renamedDeviceUsesCurrentLabelsInsteadOfStaleSavedName() {
        val renamed = Candidate("chosen", "New headset name")
        val oldNameNowBelongsToAnotherDevice = Candidate("other", "Old name")
        val result =
            EndpointIdentity.resolve(
                "Old name",
                listOf(renamed, oldNameNowBelongsToAnotherDevice),
                true,
                2,
                liveTargetLabels = setOf("New headset name", "Headset alias"),
                otherConnectedLabels = setOf("Old name"),
            )
        assertEquals(renamed, (result as Resolution.Matched).candidate)
    }

    @Test fun duplicateConnectedDeviceNamesFailEvenWithOneMatchingEndpoint() {
        val result =
            EndpointIdentity.resolve(
                target.label,
                listOf(target, other),
                true,
                2,
                liveTargetLabels = setOf(target.label),
                otherConnectedLabels = setOf("  CAR HANDS-FREE  "),
            )
        assertTrue(result is Resolution.Unavailable)
    }

    @Test fun deviceNameAndLocalAliasCanBothMatchCurrentEndpoint() {
        for (label in listOf("Factory headset name", "My alias")) {
            val endpoint = Candidate("headset", label)
            val result =
                EndpointIdentity.resolve(
                    "Saved old alias",
                    listOf(endpoint, other),
                    true,
                    2,
                    liveTargetLabels = setOf("Factory headset name", "My alias"),
                    otherConnectedLabels = setOf(other.label),
                )
            assertEquals(endpoint, (result as Resolution.Matched).candidate)
        }
    }
}
