package org.carcallrouter.companion.ui

/** Foreground connection identity only; a group/lead is not evidence of active call audio. */
data class LeAudioGroupConnection(
    val groupId: Int,
    val members: Set<String>,
    val lead: String,
) {
    val leadConnected: Boolean get() = lead in members

    companion object {
        fun resolve(
            target: String?,
            groups: Map<String, Int?>,
            leads: Map<Int, String?>,
        ): LeAudioGroupConnection? {
            val address = target?.uppercase() ?: return null
            val normalized = groups.mapKeys { it.key.uppercase() }
            // The selected member must itself be connected and have a valid current group.
            // An unreadable peer could belong to that group: do not invent its membership.
            val group = normalized[address]?.takeIf { it >= 0 } ?: return null
            if (normalized.values.any { it == null || it < 0 }) return null
            val lead = leads[group]?.takeIf { it.isNotBlank() }?.uppercase() ?: return null
            val leadGroup = normalized[lead]
            if (leadGroup != null && leadGroup != group) return null
            // Android may retain a disconnected lead for an active group. Preserve that
            // observation, explicitly distinct from a currently connected group member.
            return LeAudioGroupConnection(group, normalized.filterValues { it == group }.keys, lead)
        }
    }
}
