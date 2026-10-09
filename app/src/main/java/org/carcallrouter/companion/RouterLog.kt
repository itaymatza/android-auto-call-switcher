package org.carcallrouter.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** No numbers, call handles, account IDs, device names or raw addresses are logged. */
object RouterLog {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private val recent = java.util.ArrayDeque<String>()
    private val processSequence = AtomicLong()
    private val processId =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
    private lateinit var file: File
    private lateinit var salt: String

    @Synchronized fun initialize(context: Context) {
        file = File(context.filesDir, "router.log")
        val prefs = context.getSharedPreferences("log_identity", Context.MODE_PRIVATE)
        salt = prefs.getString("salt", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("salt", it).apply()
        }
    }

    fun deviceId(address: String?): String {
        if (address == null) return "none"
        val bytes = MessageDigest.getInstance("SHA-256").digest((salt + address.uppercase()).toByteArray())
        return bytes.take(5).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun event(
        event: String,
        detail: String,
    ) {
        val line =
            "${Instant.now()} +${SystemClock.elapsedRealtime()}ms pseq=${processSequence.incrementAndGet()} " +
                "process=$processId $event $detail"
        Log.i("CallRouteCompanion", line)
        synchronized(this) {
            recent.addLast(line)
            while (recent.size > 250) recent.removeFirst()
        }
        io.execute {
            try {
                if (file.length() > 1_000_000) {
                    rotateFiles()
                }
                file.appendText(line + "\n")
            } catch (_: Exception) {
                Log.w("CallRouteCompanion", "Diagnostic file write failed")
            }
        }
        main.post { listeners.toList().forEach { it() } }
    }

    @Synchronized fun recentText(): String = recent.joinToString("\n")

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Serialized after pending writes, so exported logs include the latest events. */
    fun export(
        context: Context,
        uri: android.net.Uri,
        done: (Boolean) -> Unit,
    ) {
        io.execute {
            val ok =
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                        writer.appendLine(
                            "Android Auto Call Switcher ${BuildConfig.VERSION_NAME}; Android SDK ${android.os.Build.VERSION.SDK_INT}",
                        )
                        writer.appendLine(
                            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; " +
                                "Android ${android.os.Build.VERSION.RELEASE}; security patch ${android.os.Build.VERSION.SECURITY_PATCH}",
                        )
                        writer.appendLine("Device addresses are salted aliases. No phone numbers are recorded.")
                        appendInvestigationGuide(writer, RouterSettings(context))
                        (MAX_ARCHIVES downTo 1).forEach { index ->
                            val archive = archive(index)
                            if (archive.exists()) writer.append(archive.readText())
                        }
                        if (file.exists()) writer.append(file.readText())
                    } != null
                } catch (_: Exception) {
                    false
                }
            main.post { done(ok) }
        }
    }

    /** Export-time configuration is separate from the historical call-boundary snapshots. */
    private fun appendInvestigationGuide(
        writer: java.io.Writer,
        settings: RouterSettings,
    ) {
        writer.append("\nDIAGNOSTIC_COVERAGE schema=2\n")
        writer.append(
            "Export-time configuration: enabled=${settings.enabled}; " +
                "target=${deviceId(settings.targetAddress)}; competitor=${deviceId(settings.competitorAddress)}\n",
        )
        writer.append("These settings describe export time; use CALL_BOUNDARY_SNAPSHOT for call-time configuration.\n")
        writer.append("Question: who owns audio before answer? Evidence: CALL_BOUNDARY_SNAPSHOT, HFP_STATE, AUDIO_DEVICE_INVENTORY.\n")
        writer.append(
            "Question: where is routing latency? Evidence: call-boundary elapsed times, ROUTE_REQUEST, HFP_QUERY_TIMING, ROUTING_TRACE.\n",
        )
        writer.append(
            "Question: did answer change the route? Compare dialing and ACTIVE boundaries, endpoint revisions, HFP audio owners and request outcomes.\n",
        )
        writer.append(
            "Question: did selected device lose audio later? Evidence: CONFIRMED_AUDIO_CHANGED and post-confirmation watch completion; unobserved intervals remain UNKNOWN.\n",
        )
        writer.append(
            "Question: did a competing request replace ours? Evidence: route request outcomes and endpoint callbacks; external requester identity is UNKNOWN.\n",
        )
        writer.append(
            "Question: are prerequisites missing? Evidence: ROUTING_TRACE permission, authorization, projection, configured device and endpoint resolution fields.\n",
        )
        writer.append(
            "Question: did Bluetooth reconnect change ownership? Evidence: HFP query triggers and fresh connected/SCO aliases within the monitored interval.\n",
        )
        writer.append(
            "Phone-calls/media profile toggles and stored connection policies: UNAVAILABLE_PUBLIC_API; disconnected does not prove disabled.\n",
        )
        writer.append("Persistent Bluetooth device priority/weight: UNAVAILABLE_PUBLIC_API.\n")
        writer.append(
            "Android Auto re-enabling a profile, head-unit model/settings, and original-car-Bluetooth mode: UNKNOWN; require device configuration evidence.\n",
        )
        writer.append(
            "Physical microphone/speaker use and seamless audible handover: NOT_VERIFIED; inventories and successful requests alone are insufficient.\n",
        )
        writer.append(
            "Observation limits: bounded asynchronous queries, stale/failed samples, service lifetime and log rotation can leave gaps.\n",
        )
        writer.append(
            "Historical events follow in chronological archive order. Missing events are not proof that a transition did not occur.\n\n",
        )
    }

    private fun rotateFiles() {
        archive(MAX_ARCHIVES).delete()
        for (index in MAX_ARCHIVES - 1 downTo 1) {
            val source = archive(index)
            if (source.exists()) source.renameTo(archive(index + 1))
        }
        file.renameTo(archive(1))
    }

    private fun archive(index: Int) = File(file.parentFile, "router.$index.log")

    private const val MAX_ARCHIVES = 3
}
