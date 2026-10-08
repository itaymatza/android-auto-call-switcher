package org.carcallrouter.companion

import android.content.Context
import android.content.SharedPreferences

class RouterSettings(
    context: Context,
) {
    data class LastSession(
        val completedAt: Long,
        val phase: String,
        val reason: String,
        val confirmation: String,
    ) {
        val result: Result
            get() =
                when {
                    confirmation == "TARGET_HFP_AUDIO_OBSERVATION_INCOMPLETE" -> Result.INCOMPLETE
                    phase == "FAILED" -> Result.FAILED
                    confirmation == "TARGET_HFP_AUDIO_UNSTABLE" -> Result.UNSTABLE
                    confirmation == "TARGET_HFP_AUDIO" &&
                        (phase == "RELEASED" || reason == "CALL_NOT_ACTIVE") -> Result.HFP_CONFIRMED
                    else -> Result.INCOMPLETE
                }
    }

    enum class Result { HFP_CONFIRMED, UNSTABLE, FAILED, INCOMPLETE }

    val prefs: SharedPreferences = context.getSharedPreferences("router_settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
        }
    val targetAddress: String? get() = prefs.getString("target", null)
    val targetName: String get() = prefs.getString("target_name", "Not selected") ?: "Not selected"
    val competitorAddress: String? get() = prefs.getString("competitor", null)
    val competitorName: String get() = prefs.getString("competitor_name", "None — no takeover retries") ?: "None"

    fun setTarget(
        address: String,
        name: String,
    ) {
        val edit =
            prefs
                .edit()
                .putString("target", address)
                .putString("target_name", name)
                .putBoolean("enabled", false)
        if (address.equals(competitorAddress, true)) edit.remove("competitor").remove("competitor_name")
        edit.apply()
    }

    fun setCompetitor(
        address: String?,
        name: String?,
    ) {
        require(address == null || !address.equals(targetAddress, true)) { "target device cannot also be the competing device" }
        prefs
            .edit()
            .putString("competitor", address)
            .putString("competitor_name", name)
            .apply()
    }

    fun markBound() {
        prefs.edit().putLong("last_bound", System.currentTimeMillis()).apply()
    }

    val lastBound: Long get() = prefs.getLong("last_bound", 0)

    fun recordLastSession(
        phase: String,
        reason: String,
        confirmation: String,
    ) {
        prefs
            .edit()
            .putLong("last_session_at", System.currentTimeMillis())
            .putString("last_session_phase", phase)
            .putString("last_session_reason", reason)
            .putString("last_session_confirmation", confirmation)
            .apply()
    }

    val lastSession: LastSession? get() {
        val completedAt = prefs.getLong("last_session_at", 0)
        if (completedAt == 0L) return null
        return LastSession(
            completedAt,
            prefs.getString("last_session_phase", "UNKNOWN") ?: "UNKNOWN",
            prefs.getString("last_session_reason", "UNKNOWN") ?: "UNKNOWN",
            prefs.getString("last_session_confirmation", "NONE") ?: "NONE",
        )
    }

    companion object {
        /** Preferences which can change an in-flight routing decision. */
        internal val ROUTING_CONFIGURATION_KEYS =
            setOf(
                "enabled",
                "target",
                "target_name",
                "competitor",
                "competitor_name",
            )
    }
}
