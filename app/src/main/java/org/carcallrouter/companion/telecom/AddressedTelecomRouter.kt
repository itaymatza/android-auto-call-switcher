package org.carcallrouter.companion.telecom

import android.os.OutcomeReceiver
import android.os.SystemClock
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import org.carcallrouter.companion.core.EndpointIdentity

/**
 * API 34+ Telecom endpoint boundary. Requests use only live CallEndpoint instances supplied by
 * Telecom. Endpoint identifiers are session-scoped and are never persisted as device identity.
 */
class AddressedTelecomRouter(
    private val service: InCallService,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    data class Target(
        val endpoint: CallEndpoint?,
        val reason: String,
        val basis: EndpointIdentity.Basis? = null,
    )

    enum class RequestOrigin { SELF, EXTERNAL }

    data class RequestTicket(
        val generation: Long,
        val id: Long,
        val createdAt: Long,
        val expiresAt: Long,
    )

    data class RequestObservation(
        val origin: RequestOrigin,
        val generation: Long,
        val requestId: Long?,
        val ageMs: Long?,
        val pendingCount: Int,
        val expiredCount: Int,
    )

    private data class RequestMarker(
        val ticket: RequestTicket,
        val endpointId: String,
    )

    private var available: List<CallEndpoint> = emptyList()
    private var availableSnapshotReceived = false
    private var availableRevision = 0L
    private var current: CallEndpoint? = null

    // API 37 may report a request before or after its result/endpoint callbacks. Markers are
    // generation-bound and expire so a missing OEM callback cannot misclassify a future session.
    private val ownRequests = ArrayDeque<RequestMarker>()
    private var generation = 1L
    private var nextRequestId = 1L

    fun updateAvailable(endpoints: List<CallEndpoint>) {
        availableSnapshotReceived = true
        availableRevision++
        available = endpoints.toList()
        val ids = available.map { it.identifier.toString() }.toSet()
        if (current?.identifier?.toString() !in ids) current = null
    }

    fun updateCurrent(endpoint: CallEndpoint) {
        current = endpoint
    }

    fun current(): CallEndpoint? {
        current?.let { return it }
        return try {
            service.currentCallEndpoint
                .takeIf { endpoint ->
                    availableSnapshotReceived && available.any { it.identifier == endpoint.identifier }
                }?.also { current = it }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun hasAvailableSnapshot(): Boolean = availableSnapshotReceived

    fun endpointRevision(): Long = availableRevision

    fun target(
        targetHfpConnected: Boolean,
        connectedHfpCount: Int,
        liveTargetLabels: Set<String>,
        otherConnectedLabels: Set<String>,
        liveLabelsKnown: Boolean,
    ): Target {
        val endpoints = available.filter { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
        val candidates =
            endpoints.map {
                EndpointIdentity.Candidate(it.identifier.toString(), it.endpointName.toString())
            }
        return when (
            val resolution =
                EndpointIdentity.resolve(
                    candidates,
                    targetHfpConnected,
                    connectedHfpCount,
                    liveTargetLabels,
                    otherConnectedLabels,
                    liveLabelsKnown,
                )
        ) {
            is EndpointIdentity.Resolution.Matched ->
                Target(
                    endpoint = endpoints.single { it.identifier.toString() == resolution.candidate.id },
                    reason = "Matched current Telecom endpoint",
                    basis = resolution.basis,
                )
            is EndpointIdentity.Resolution.Unavailable -> Target(null, resolution.reason)
        }
    }

    fun isCurrent(endpoint: CallEndpoint?): Boolean =
        endpoint != null &&
            current()?.identifier == endpoint.identifier

    fun observeRequest(endpoint: CallEndpoint): RequestObservation {
        val observedAt = now()
        val expired = pruneExpired(observedAt)
        val endpointId = endpoint.identifier.toString()
        val marker = ownRequests.firstOrNull { it.ticket.generation == generation && it.endpointId == endpointId }
        if (marker != null) ownRequests.remove(marker)
        return RequestObservation(
            origin = if (marker == null) RequestOrigin.EXTERNAL else RequestOrigin.SELF,
            generation = generation,
            requestId = marker?.ticket?.id,
            ageMs = marker?.let { (observedAt - it.ticket.createdAt).coerceAtLeast(0) },
            pendingCount = ownRequests.count { it.ticket.generation == generation },
            expiredCount = expired,
        )
    }

    fun clearSession(clearEndpoints: Boolean = false) {
        ownRequests.clear()
        generation++
        if (clearEndpoints) {
            available = emptyList()
            availableSnapshotReceived = false
            availableRevision = 0L
            current = null
        }
    }

    fun generation(): Long = generation

    fun isCurrentGeneration(ticket: RequestTicket): Boolean = ticket.generation == generation

    fun request(
        endpoint: CallEndpoint,
        started: (RequestTicket) -> Unit,
        accepted: (RequestTicket) -> Unit,
        rejected: (RequestTicket, CallEndpointException) -> Unit,
    ): RequestTicket {
        check(available.any { it === endpoint }) {
            "Endpoint is no longer in Telecom's current callback set"
        }
        val createdAt = now()
        pruneExpired(createdAt)
        val ticket =
            RequestTicket(
                generation = generation,
                id = nextRequestId++,
                createdAt = createdAt,
                expiresAt = createdAt + REQUEST_MARKER_TTL_MS,
            )
        ownRequests.addLast(RequestMarker(ticket, endpoint.identifier.toString()))
        try {
            started(ticket)
            service.requestCallEndpointChange(
                endpoint,
                service.mainExecutor,
                object : OutcomeReceiver<Void?, CallEndpointException> {
                    override fun onResult(result: Void?) = accepted(ticket)

                    // Keep the marker until the API 37 request callback is consumed. That callback
                    // can be delivered after this result callback.
                    override fun onError(error: CallEndpointException) = rejected(ticket, error)
                },
            )
        } catch (error: RuntimeException) {
            ownRequests.removeAll { it.ticket == ticket }
            throw error
        }
        return ticket
    }

    private fun pruneExpired(at: Long): Int {
        val before = ownRequests.size
        ownRequests.removeAll { it.ticket.generation != generation || it.ticket.expiresAt < at }
        return before - ownRequests.size
    }

    companion object {
        internal const val REQUEST_MARKER_TTL_MS = 10_000L
    }
}
