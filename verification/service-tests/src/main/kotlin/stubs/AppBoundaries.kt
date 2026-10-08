package org.carcallrouter.companion
import android.content.Context

object Access {
    var authorization = true
    var runtime = true

    fun ongoingCalls(c: Context) = authorization

    fun runtimeGranted(c: Context) = runtime
}

object RouterLog {
    val events = mutableListOf<Pair<String, String>>()

    fun event(
        tag: String,
        message: String,
    ) {
        events.add(tag to message)
    }

    fun deviceId(value: String?) = value ?: "none"

    fun recentText() = events.joinToString("\n")
}

object ProcessDiagnostics {
    fun snapshot(c: Context) = "processAgeMs=0; uiState=NEVER_OPENED"

    fun traceFields(c: Context): Array<Pair<String, Any?>> =
        arrayOf(
            "app_version" to "test",
            "app_version_code" to 1,
            "sdk" to 37,
            "android_release" to "test",
            "security_patch" to "test",
            "manufacturer" to "test",
            "model" to "test",
            "process_age_ms" to 0,
            "ui_state" to "NEVER_OPENED",
            "importance" to 100,
            "interactive" to true,
            "battery_exempt" to true,
            "standby_bucket" to 10,
        )
}

class ProjectionMonitor(
    c: Context,
    private val changed: (Boolean?) -> Unit,
) : AutoCloseable {
    init {
        instances.add(this)
    }

    fun requestRefresh() {
        changed(current)
    }

    fun start() {
        changed(current)
    }

    override fun close() {
        instances.remove(this)
    }

    companion object {
        var current: Boolean? = true
        val instances = mutableListOf<ProjectionMonitor>()

        fun emit(value: Boolean?) {
            current = value
            instances.toList().forEach { it.changed(value) }
        }
    }
}
