@file:Suppress("UNCHECKED_CAST")

package com.willfp.ecoskills.stats

import com.willfp.eco.core.map.nestedMap
import com.willfp.ecoskills.api.getBaseStatLevel
import com.willfp.ecoskills.api.modifiers.ModifierOperation
import com.willfp.ecoskills.api.modifiers.StatModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

// Player UUID -> Stat Modifier UUID -> Stat Modifier
private val trackedStatModifiers = nestedMap<UUID, UUID, StatModifier>()

/** No default bucket and no profile reads. Must run on main. */
fun peekStatModifiers(uuid: UUID): List<StatModifier> =
    trackedStatModifiers.getIfPresent(uuid)?.values?.take(513) ?: emptyList()

fun peekStatModifierCount(uuid: UUID): Int = trackedStatModifiers.getIfPresent(uuid)?.size ?: 0

internal val Player.statModifiers: StatModifiers
    get() = StatModifiers(this)

class StatModifiers(
    private val player: Player
) {
    internal var onChanged: (Set<Stat>) -> Unit = { invalidateModifiedStats(player, it) }

    fun add(modifier: StatModifier) {
        val previous = trackedStatModifiers[player.uniqueId].put(modifier.uuid, modifier)
        if (previous != modifier) onChanged(setOfNotNull(previous?.stat, modifier.stat))
    }

    operator fun plusAssign(modifier: StatModifier) = add(modifier)

    fun remove(uuid: UUID): StatModifier? {
        val removed = trackedStatModifiers[player.uniqueId].remove(uuid) ?: return null
        onChanged(setOf(removed.stat))
        return removed
    }

    operator fun minusAssign(uuid: UUID) {
        remove(uuid)
    }

    operator fun minusAssign(modifier: StatModifier) {
        remove(modifier.uuid)
    }

    fun getModifiers(): List<StatModifier> {
        return trackedStatModifiers[player.uniqueId].values.toList()
    }

    fun getModifiers(stat: Stat): List<StatModifier> {
        return getModifiers().filter { it.stat.id == stat.id }
    }

    fun getModifier(uuid: UUID): StatModifier? {
        return getModifiers().firstOrNull { it.uuid == uuid }
    }

    /**
     * Get how much a stat is modified by.
     */
    fun getBonusStatLevel(stat: Stat): Int {
        return getModifiedValue(stat) - player.getBaseStatLevel(stat)
    }

    /**
     * Get the modified value of a stat.
     */
    fun getModifiedValue(stat: Stat): Int = getStatValue(stat).toInt()

    fun getStatValue(stat: Stat): Double =
        modifiedStatValue(player.getBaseStatLevel(stat).toDouble(), getModifiers(stat))

    fun getBonusStatValue(stat: Stat): Double = getStatValue(stat) - player.getBaseStatLevel(stat)

}

// Little fixer-upper
object StatModifierListener : Listener {
    @EventHandler
    fun onLeave(event: PlayerQuitEvent) {
        trackedStatModifiers.remove(event.player.uniqueId)
    }
}
