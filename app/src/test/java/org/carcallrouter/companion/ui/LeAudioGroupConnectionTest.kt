package org.carcallrouter.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeAudioGroupConnectionTest {
    private val left = "AA:BB:CC:DD:EE:01"
    private val right = "AA:BB:CC:DD:EE:02"
    private val peer = "11:22:33:44:55:66"

    @Test fun eitherConnectedEarbudResolvesTheSameGroup() {
        val members = mapOf(left to 7, right to 7, peer to 8)
        val leads = mapOf(7 to left, 8 to peer)
        val group = LeAudioGroupConnection.resolve(right.lowercase(), members, leads)!!
        assertEquals(LeAudioGroupConnection.resolve(left, members, leads), group)
        assertEquals(setOf(left, right), group.members)
        assertEquals(left, group.lead)
        assertTrue(group.leadConnected)
    }

    @Test fun selectedDisconnectedMemberCannotBorrowPeersGroup() {
        assertNull(LeAudioGroupConnection.resolve(left, mapOf(right to 7), mapOf(7 to right)))
        assertNull(LeAudioGroupConnection.resolve(null, mapOf(right to 7), mapOf(7 to right)))
    }

    @Test fun invalidOrUnknownGroupNeverEstablishesIdentity() {
        for (group in listOf(null, -1)) {
            assertNull(LeAudioGroupConnection.resolve(left, mapOf(left to group), mapOf(7 to left)))
            assertNull(LeAudioGroupConnection.resolve(left, mapOf(left to 7, peer to group), mapOf(7 to left)))
        }
    }

    @Test fun missingLeadNeverEstablishesIdentity() {
        for (lead in listOf(null, "")) {
            assertNull(LeAudioGroupConnection.resolve(left, mapOf(left to 7), mapOf(7 to lead)))
        }
    }

    @Test fun leadFromAnotherConnectedGroupIsRejected() {
        assertNull(LeAudioGroupConnection.resolve(left, mapOf(left to 7, peer to 8), mapOf(7 to peer)))
    }

    @Test fun disconnectedLeadIsExplicitlyDistinctFromConnectedMember() {
        val group = LeAudioGroupConnection.resolve(right, mapOf(right to 7), mapOf(7 to left))!!
        assertEquals(setOf(right), group.members)
        assertEquals(left, group.lead)
        assertFalse(group.leadConnected)
    }

    @Test fun newSnapshotCanChangeGroupAndLeadWithoutReusingSavedIdentity() {
        val before = LeAudioGroupConnection.resolve(right, mapOf(left to 7, right to 7), mapOf(7 to left))!!
        val after = LeAudioGroupConnection.resolve(right, mapOf(right to 9), mapOf(9 to right))!!
        assertEquals(7, before.groupId)
        assertEquals(9, after.groupId)
        assertEquals(right, after.lead)
        assertEquals(setOf(right), after.members)
    }
}
