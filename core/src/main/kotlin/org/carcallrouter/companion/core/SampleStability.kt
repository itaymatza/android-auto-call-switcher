package org.carcallrouter.companion.core

/** Consecutive fresh samples of one value; never proof of continuous physical audio. */
internal class SampleStability<T : Any>(
    private val maxSampleAgeMs: Long,
) {
    private data class Streak<T>(
        val value: T,
        val since: Long,
        val lastSampleAt: Long,
    )

    private var streak: Streak<T>? = null
    val value: T? get() = streak?.value
    val since: Long? get() = streak?.since

    fun observe(
        value: T?,
        sampledAt: Long?,
        now: Long,
    ) {
        if (value == null || sampledAt == null || now - sampledAt !in 0..maxSampleAgeMs) {
            reset()
            return
        }
        val previous = streak
        streak =
            if (previous != null && previous.value == value && sampledAt - previous.lastSampleAt in 0..maxSampleAgeMs) {
                previous.copy(lastSampleAt = sampledAt)
            } else {
                Streak(value, sampledAt, sampledAt)
            }
    }

    fun isStableFor(durationMs: Long): Boolean = streak?.let { it.lastSampleAt - it.since >= durationMs } == true

    fun reset() {
        streak = null
    }
}
