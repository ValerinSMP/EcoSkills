package com.willfp.ecoskills.skills

import java.util.UUID

internal fun offensiveSkillMethod(isProjectile: Boolean, supportedProjectile: Boolean): String? =
    if (isProjectile) { if (supportedProjectile) "ranged" else null } else "melee"

internal fun matchesSkillMobType(target: String?, allowed: Collection<String>): Boolean =
    target != null && allowed.any { it.equals(target, ignoreCase = true) }

/** Eligibility for the XP-only death source; never controls general combat callbacks. */
internal class MobKillXPPolicy {
    private val accepted = LinkedHashSet<UUID>()

    fun accept(id: UUID, cancelled: Boolean, dead: Boolean, health: Double,
               playerVictim: Boolean, npc: Boolean, onlineKiller: Boolean): Boolean {
        if (cancelled || !dead || !health.isFinite() || health > 0 || playerVictim || npc || !onlineKiller) {
            return false
        }
        if (!accepted.add(id)) return false
        // Bounded deduplication. EntityDeathEvent, not synthetic kill notifications, is authoritative.
        if (accepted.size > 4096) accepted.remove(accepted.first())
        return true
    }
}
