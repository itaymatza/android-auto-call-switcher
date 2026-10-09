package android.telecom
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.OutcomeReceiver
import android.os.SystemClock
import java.io.FileDescriptor
import java.io.PrintWriter

class Call(
    var details: Details,
) {
    class Details(
        var state: Int,
    )

    var rejection: String? = null
    val callbacks = linkedSetOf<Callback>()

    fun registerCallback(
        c: Callback,
        h: Handler,
    ) {
        callbacks.add(c)
    }

    fun unregisterCallback(c: Callback) {
        callbacks.remove(c)
    }

    fun deliverState(state: Int) {
        details.state = state
        callbacks.toList().forEach { it.onStateChanged(this, state) }
    }

    fun deliverDetails() {
        callbacks.toList().forEach { it.onDetailsChanged(this, details) }
    }

    abstract class Callback {
        open fun onStateChanged(
            call: Call,
            state: Int,
        ) {}

        open fun onDetailsChanged(
            call: Call,
            details: Details,
        ) {}

        open fun onChildrenChanged(
            call: Call,
            children: MutableList<Call>,
        ) {}
    }

    companion object {
        const val STATE_NEW = 0
        const val STATE_DIALING = 1
        const val STATE_RINGING = 2
        const val STATE_HOLDING = 3
        const val STATE_ACTIVE = 4
        const val STATE_DISCONNECTED = 7
        const val STATE_SELECT_PHONE_ACCOUNT = 8
        const val STATE_CONNECTING = 9
        const val STATE_DISCONNECTING = 10
    }
}

class CallEndpoint(
    val endpointName: CharSequence,
    val endpointType: Int,
    val identifier: java.util.UUID = java.util.UUID.randomUUID(),
) {
    constructor(endpointType: Int) : this("", endpointType)

    companion object {
        const val TYPE_UNKNOWN = -1
        const val TYPE_EARPIECE = 1
        const val TYPE_BLUETOOTH = 2
        const val TYPE_WIRED_HEADSET = 3
        const val TYPE_SPEAKER = 4
        const val TYPE_STREAMING = 5
    }
}

class CallEndpointException(
    val code: Int,
    message: String = "test endpoint error",
) : RuntimeException(message) {
    companion object {
        const val ERROR_ENDPOINT_DOES_NOT_EXIST = 1
        const val ERROR_REQUEST_TIME_OUT = 2
        const val ERROR_ANOTHER_REQUEST = 3
        const val ERROR_UNSPECIFIED = 4
    }
}

open class InCallService : Context() {
    data class PendingRequest(
        val endpoint: CallEndpoint,
        val receiver: OutcomeReceiver<Void?, CallEndpointException>,
    )

    val issuedRequests = mutableListOf<Pair<Long, String>>()
    val pendingRequests = mutableListOf<PendingRequest>()
    var autoCompleteRequests = true
    var requestException: RuntimeException? = null
    var currentCallEndpoint: CallEndpoint = CallEndpoint("Handset", CallEndpoint.TYPE_EARPIECE)

    fun requestCallEndpointChange(
        endpoint: CallEndpoint,
        executor: java.util.concurrent.Executor,
        receiver: OutcomeReceiver<Void?, CallEndpointException>,
    ) {
        requestException?.let { throw it }
        pendingRequests.removeFirstOrNull()?.let { old ->
            executor.execute { old.receiver.onError(CallEndpointException(CallEndpointException.ERROR_ANOTHER_REQUEST)) }
        }
        issuedRequests.add(SystemClock.elapsedRealtime() to endpoint.identifier.toString())
        if (autoCompleteRequests) executor.execute { receiver.onResult(null) } else pendingRequests.add(PendingRequest(endpoint, receiver))
    }

    fun completeLatest() {
        pendingRequests.removeLastOrNull()?.receiver?.onResult(null)
    }

    fun failLatest(code: Int) {
        pendingRequests.removeLastOrNull()?.receiver?.onError(CallEndpointException(code))
    }

    open fun onCreate() {}

    open fun onBind(intent: Intent): IBinder? = object : IBinder {}

    open fun onCallAdded(call: Call) {}

    open fun onCallRemoved(call: Call) {}

    open fun onCallEndpointChanged(callEndpoint: CallEndpoint) {}

    open fun onCallEndpointRequested(callEndpoint: CallEndpoint) {}

    open fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {}

    open fun onUnbind(intent: Intent) = false

    open fun onDestroy() {}

    open fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>,
    ) {}
}
