package org.carcallrouter.companion.telecom
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call

class CellularClassifier(
    c: Context,
    private val changed: () -> Unit,
) : AutoCloseable {
    fun assess(call: Call): org.carcallrouter.companion.core.CallSafety.Assessment =
        if (pending) {
            org.carcallrouter.companion.core.CallSafety.Assessment.Pending
        } else {
            call.rejection?.let(org.carcallrouter.companion.core.CallSafety.Assessment::Unsafe)
                ?: org.carcallrouter.companion.core.CallSafety.Assessment.Safe
        }

    fun diagnosticFields(): Array<Pair<String, Any?>> = emptyArray()

    fun clear() = Unit

    fun remove(call: Call) = Unit

    override fun close() = Unit

    init {
        instances.add(this)
    }

    companion object {
        var pending = false
        val instances = mutableListOf<CellularClassifier>()

        fun complete() {
            pending = false
            instances.forEach { it.changed() }
        }
    }
}

class HfpMonitor(
    c: Context,
    private val changed: () -> Unit,
) : AutoCloseable {
    private var started = false
    private var pending = false
    private var closed = false
    private var sampledKnown = false
    private var sampledDevices = emptySet<String>()
    private var sampledAudioDevices = emptySet<String>()
    val known get() = started && sampledKnown
    val connected get() = if (started) sampledDevices else emptySet()
    val audioConnected get() = if (started) sampledAudioDevices else emptySet()
    val deviceLabels get() = if (known) labels.filterKeys { it in connected } else emptyMap()

    fun diagnosticFields(): Array<Pair<String, Any?>> =
        arrayOf(
            "hfp_sample_quality" to if (known) "OBSERVED" else "UNKNOWN",
            "hfp_query_trigger" to "test",
            "hfp_query_ms" to queryDelayMs,
            "hfp_proxy_ready" to started,
            "hfp_negative_is_ambiguous" to true,
        )

    var sampledAt: Long? = null
        private set
    var sampleSequence = 0L
        private set

    init {
        instances.add(this)
    }

    fun start() {
        if (started) return
        started = true
        starts++
        refresh("startup")
    }

    fun refresh(
        trigger: String = "unspecified",
        notify: Boolean = true,
    ) {
        if (!started || closed || pending) return
        if (queryDelayMs > 0) {
            pending = true
            val observedAt = SystemClock.elapsedRealtime()
            Handler(Looper.getMainLooper()).postDelayed({
                pending = false
                if (!closed) {
                    sampledKnown = isKnown
                    sampledDevices = devices
                    sampledAudioDevices = audioDevices
                    sampledAt = observedAt
                    sampleSequence++
                    refreshes++
                    changed()
                }
            }, queryDelayMs)
            return
        }
        sampledKnown = isKnown
        sampledDevices = devices
        sampledAudioDevices = audioDevices
        sampledAt = SystemClock.elapsedRealtime()
        sampleSequence++
        refreshes++
        if (notify) changed()
    }

    override fun close() {
        closed = true
        instances.remove(this)
    }

    companion object {
        var labels: Map<String, Set<String>> = emptyMap()
        const val MAX_SAMPLE_AGE_MS = 750L
        var queryDelayMs = 0L
        var isKnown = true
        var devices = setOf<String>()
        var audioDevices = setOf<String>()
        var starts = 0
        var refreshes = 0
        val instances = mutableListOf<HfpMonitor>()

        fun emit(
            value: Set<String>,
            known: Boolean = true,
        ) {
            devices = value
            isKnown = known
            instances.filter { it.started }.forEach { it.refresh("broadcast") }
        }
    }
}

class AuthorizationMonitor(
    c: Context,
    private val changed: () -> Unit,
) : AutoCloseable {
    fun sample(): Boolean? =
        if (org.carcallrouter.companion.Access.queryDelayMs >
            0
        ) {
            null
        } else {
            org.carcallrouter.companion.Access.authorization
        }

    fun diagnosticFields(): Array<Pair<String, Any?>> = emptyArray()

    override fun close() = Unit
}
