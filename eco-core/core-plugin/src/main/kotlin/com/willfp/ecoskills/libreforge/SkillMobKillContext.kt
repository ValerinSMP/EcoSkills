package com.willfp.ecoskills.libreforge

import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.entity.EntityDeathEvent

/** Trigger-local immutable identity; not published as an additional Bukkit death. */
class SkillMobKillContext(val death: EntityDeathEvent, val targetType: String) : Event(), Cancellable {
    override fun isCancelled() = death.isCancelled
    override fun setCancelled(cancel: Boolean) { death.isCancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS: HandlerList = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
