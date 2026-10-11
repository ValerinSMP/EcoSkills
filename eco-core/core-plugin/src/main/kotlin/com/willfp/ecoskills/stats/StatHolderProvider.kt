package com.willfp.ecoskills.stats

import com.willfp.ecoskills.collectProvidedLevelHolders
import com.willfp.ecoskills.effects.Effects
import com.willfp.ecoskills.gui.components.invalidateStatIcon
import com.willfp.ecoskills.skills.isInDisabledWorld
import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.HolderProvider
import com.willfp.libreforge.ProvidedHolder
import com.willfp.libreforge.SimpleProvidedHolder
import com.willfp.libreforge.get
import com.willfp.libreforge.invalidate
import com.willfp.libreforge.toDispatcher
import org.bukkit.entity.Player

/** Stable native provider; only this player's EcoSkills provider is dirtied by a modifier. */
internal object StatHolderProvider : HolderProvider {
    override val id = "ecoskills:levels"

    override fun provide(dispatcher: Dispatcher<*>): Collection<ProvidedHolder> {
        val player = dispatcher.get<Player>() ?: return emptyList()
        if (player.isInDisabledWorld) return emptyList()
        return collectProvidedLevelHolders(
            Effects.values() union Stats.values(),
            { if (it is Stat) it.getLevelHolder(it.getActualValue(player))
              else it.getLevelHolder(it.getActualLevel(player)) },
            { SimpleProvidedHolder(it) }
        )
    }
}

internal fun invalidateModifiedStats(player: Player, stats: Set<Stat>) {
    if (!player.isOnline) return
    stats.forEach { invalidateStatIcon(player, it) }
    player.toDispatcher().invalidate(StatHolderProvider)
}
