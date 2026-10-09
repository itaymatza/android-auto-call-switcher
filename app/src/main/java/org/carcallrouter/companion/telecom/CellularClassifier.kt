package org.carcallrouter.companion.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.core.CallSafety
import org.carcallrouter.companion.core.SingleFlightQuery
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owner-thread admission with bounded off-thread protected queries; no number/account diagnostics. */
class CellularClassifier internal constructor(
    private val context: Context,
    queryWorker: Executor = worker,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private data class Key(
        val account: PhoneAccountHandle?,
        val handle: Uri?,
    )

    private var tracked: Call? = null
    private var key: Key? = null
    private var cached: CallSafety.Assessment? = null
    private var sampledAt: Long? = null
    private var refreshAt = 0L
    private var closed = false
    private var quality = "UNSAMPLED"
    private var queryMs: Long? = null
    private val query =
        SingleFlightQuery<String?>(queryWorker, Executor { handler.post(it) }, SystemClock::elapsedRealtime) { result, _, started ->
            val now = SystemClock.elapsedRealtime()
            queryMs = now - started
            refreshAt = now + REFRESH_INTERVAL_MS
            sampledAt = null
            cached =
                when {
                    !Access.runtimeGranted(context) -> {
                        quality = "PERMISSION_MISSING"
                        CallSafety.Assessment.Unsafe("Required runtime permissions not granted")
                    }
                    now - started !in 0..MAX_EVIDENCE_AGE_MS -> {
                        quality = "STALE"
                        null
                    }
                    result.isFailure -> {
                        quality = "ERROR"
                        CallSafety.Assessment.Unsafe("Call classification unavailable")
                    }
                    else -> {
                        quality = "OBSERVED"
                        sampledAt = started
                        result.getOrNull()?.let(CallSafety.Assessment::Unsafe) ?: CallSafety.Assessment.Safe
                    }
                }
            changed()
        }

    fun assess(call: Call): CallSafety.Assessment {
        if (closed) return CallSafety.Assessment.Unsafe("Call classifier closed")
        if (!Access.runtimeGranted(context)) {
            clear()
            quality = "PERMISSION_MISSING"
            return CallSafety.Assessment.Unsafe("Required runtime permissions not granted")
        }
        val details = call.details ?: return reject("Call details unavailable")
        val scope =
            CallSafety.Evidence(
                simAccount = null,
                emergencyFlag = details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL),
                emergencyCallbackMode = details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE),
                externalOrSelfManaged =
                    details.hasProperty(Call.Details.PROPERTY_IS_EXTERNAL_CALL) || details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED),
                conference = details.hasProperty(Call.Details.PROPERTY_CONFERENCE) || call.children.isNotEmpty(),
                telephoneHandlePresent = false,
                numberIsEmergency = null,
            )
        CallSafety.scopeRejection(scope)?.let { return reject(it) }
        // Immutable handle/account identity stays local; no number/account diagnostics.
        val nextKey = Key(details.accountHandle, details.handle)
        if (nextKey.account == null) return reject("SIM-backed phone account not verified")
        if (tracked !== call || key != nextKey) {
            clear()
            tracked = call
            key = nextKey
        }
        val now = SystemClock.elapsedRealtime()
        if (sampledAt?.let { now - it !in 0..MAX_EVIDENCE_AGE_MS } == true) {
            cached = null
            quality = "STALE"
        }
        if (now >= refreshAt) query.submit { readRejection(nextKey, scope) }
        return cached ?: CallSafety.Assessment.Pending
    }

    private fun reject(reason: String): CallSafety.Assessment {
        clear()
        quality = "SCOPE_REJECTED"
        return CallSafety.Assessment.Unsafe(reason)
    }

    @SuppressLint("MissingPermission")
    private fun readRejection(
        candidate: Key,
        scope: CallSafety.Evidence,
    ): String? {
        val sim =
            try {
                candidate.account?.let { telecom?.getPhoneAccount(it)?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) }
            } catch (_: RuntimeException) {
                null
            }
        if (sim != true) return CallSafety.rejection(scope.copy(simAccount = sim))
        val handle = candidate.handle
        val number =
            if (handle?.scheme ==
                "tel"
            ) {
                PhoneNumberUtils.extractNetworkPortion(handle.schemeSpecificPart)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        val emergency =
            number?.let {
                try {
                    telephony?.isEmergencyNumber(it)
                } catch (_: RuntimeException) {
                    null
                }
            }
        return CallSafety.rejection(scope.copy(simAccount = sim, telephoneHandlePresent = number != null, numberIsEmergency = emergency))
    }

    fun diagnosticFields(now: Long = SystemClock.elapsedRealtime()): Array<Pair<String, Any?>> =
        arrayOf(
            "call_safety_quality" to quality,
            "call_safety_sample_age_ms" to sampledAt?.let { (now - it).coerceAtLeast(0) },
            "call_safety_query_ms" to queryMs,
        )

    fun remove(call: Call) {
        if (tracked === call) clear()
    }

    fun clear() {
        query.invalidate()
        tracked = null
        key = null
        cached = null
        sampledAt = null
        refreshAt = 0
        queryMs = null
        quality = "UNSAMPLED"
    }

    override fun close() {
        closed = true
        clear()
        query.close()
    }

    companion object {
        const val MAX_EVIDENCE_AGE_MS = 750L
        private const val REFRESH_INTERVAL_MS = 250L
        private val worker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-call-safety-query").apply { isDaemon = true }
            })
    }
}
