package org.carcallrouter.companion

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.carcallrouter.companion.core.DiagnosticSampler
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Privacy-safe process/UI facts used to diagnose cold-start and background binding failures. */
object ProcessDiagnostics {
    private val startedAt = SystemClock.elapsedRealtime()
    private val handler = Handler(Looper.getMainLooper())
    private val worker =
        ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
            Thread(task, "router-process-diagnostics").apply { isDaemon = true }
        })
    private var sampler: DiagnosticSampler<Map<String, Any?>>? = null

    private fun environment(context: Context): Map<String, Any?> {
        val app = context.applicationContext
        val source =
            sampler ?: DiagnosticSampler(
                worker,
                Executor { handler.post(it) },
                SystemClock::elapsedRealtime,
                read = { readEnvironment(app) },
                observed = { RouterLog.event("PROCESS_ENVIRONMENT", it.entries.joinToString("; ") { (key, value) -> "$key=$value" }) },
            ).also { sampler = it }
        val observation = source.sample()
        return observation?.value.orEmpty() +
            mapOf(
                "process_probe_quality" to if (observation == null) "UNAVAILABLE" else "OBSERVED",
                "process_probe_age_ms" to observation?.let { SystemClock.elapsedRealtime() - it.sampledAt },
            )
    }

    private fun readEnvironment(context: Context): Map<String, Any?> {
        val processInfo = ActivityManager.RunningAppProcessInfo()
        val importance =
            runCatching {
                ActivityManager.getMyMemoryState(processInfo)
                processInfo.importance
            }.getOrNull()
        val power = context.getSystemService(PowerManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        return mapOf(
            "importance" to importance,
            "interactive" to runCatching { power?.isInteractive }.getOrNull(),
            "battery_exempt" to runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
            "standby_bucket" to runCatching { usage?.appStandbyBucket }.getOrNull(),
            "previous_exit" to readPreviousExit(context),
        )
    }

    @Volatile
    private var uiState = "NEVER_OPENED"

    fun processAgeMs(): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)

    fun markUi(
        state: String,
        serviceBound: Boolean,
    ) {
        uiState = state
        RouterLog.event(
            "UI_LIFECYCLE",
            "state=$state; serviceBound=$serviceBound; processAgeMs=${processAgeMs()}",
        )
    }

    fun snapshot(context: Context): String =
        "processAgeMs=${processAgeMs()}; uiState=$uiState; " +
            environment(context).entries.joinToString("; ") { (key, value) -> "$key=$value" }

    /** Structured, privacy-safe environment fields attached to each routing session. */
    fun traceFields(context: Context): Array<Pair<String, Any?>> =
        arrayOf(
            "app_version" to BuildConfig.VERSION_NAME,
            "app_version_code" to BuildConfig.VERSION_CODE,
            "sdk" to Build.VERSION.SDK_INT,
            "android_release" to Build.VERSION.RELEASE,
            "security_patch" to Build.VERSION.SECURITY_PATCH,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "process_age_ms" to processAgeMs(),
            "ui_state" to uiState,
            *environment(context).toList().toTypedArray(),
        )

    private fun readPreviousExit(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return "unavailable"
        val exit =
            runCatching { manager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull()
                ?: return "none"
        val ageMs = (System.currentTimeMillis() - exit.timestamp).coerceAtLeast(0)
        return "reason=${exitReasonName(exit.reason)}; status=${exit.status}; ageMs=$ageMs"
    }

    private fun exitReasonName(reason: Int): String =
        when (reason) {
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            else -> "UNKNOWN_$reason"
        }
}
