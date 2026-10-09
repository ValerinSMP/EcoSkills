package com.willfp.ecoskills.libreforge

import com.willfp.ecoskills.plugin
import org.bukkit.entity.Player

internal class MagicDiagnosticLimit {
    private var window: Long? = null
    private var count = 0

    fun allow(enabled: Boolean, filter: String, player: String, now: Long): Boolean {
        if (!enabled || filter.isBlank() || (filter != "*" && !filter.equals(player, true))) return false
        if (window == null || now - window!! >= 1_000_000_000L) {
            window = now
            count = 0
        }
        if (count >= 5) return false
        count++
        return true
    }
}

private val diagnosticLimit = MagicDiagnosticLimit()

internal fun traceMagicCost(player: Player, message: () -> String) {
    if (diagnosticLimit.allow(
            plugin.configYml.getBool("diagnostics.magic-cost.enabled"),
            plugin.configYml.getString("diagnostics.magic-cost.player"),
            player.name,
            System.nanoTime()
        )) plugin.logger.info(message())
}
