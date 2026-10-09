package com.willfp.ecoskills.regeneration

import com.willfp.ecoskills.plugin
import com.willfp.ecoskills.skills.isInDisabledWorld
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityExhaustionEvent

object RegenerationHungerListener : Listener {
    @EventHandler(ignoreCancelled = true)
    fun handle(event: EntityExhaustionEvent) {
        if (event.exhaustionReason != EntityExhaustionEvent.ExhaustionReason.REGEN) return

        val player = event.entity as? Player ?: return
        if (player.isInDisabledWorld) return

        val config = plugin.configYml
        if (!config.getBool("natural-regeneration-hunger.enabled")) return

        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        val multiplier = regenerationHungerMultiplier(
            maxHealth,
            config.getDouble("natural-regeneration-hunger.base-health"),
            config.getDouble("natural-regeneration-hunger.minimum-multiplier")
        )

        event.exhaustion = (event.exhaustion * multiplier).toFloat()
    }
}

internal fun regenerationHungerMultiplier(
    maxHealth: Double,
    baseHealth: Double,
    minimumMultiplier: Double
): Double {
    val safeBaseHealth = baseHealth.coerceAtLeast(1.0)
    if (maxHealth <= safeBaseHealth) return 1.0

    return (safeBaseHealth / maxHealth)
        .coerceIn(minimumMultiplier.coerceIn(0.0, 1.0), 1.0)
}
