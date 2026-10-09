package com.willfp.ecoskills.libreforge

import com.willfp.ecoskills.skills.MobKillXPPolicy
import com.willfp.ecoskills.skills.offensiveSkillMethod
import com.willfp.ecoskills.plugin
import com.willfp.eco.core.entities.Entities
import com.willfp.eco.core.entities.CustomEntity
import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.triggers.Trigger
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.TriggerParameter
import org.bukkit.attribute.Attribute
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Player
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDamageEvent
import java.util.WeakHashMap

/** A separate source for skill XP. Existing kill/headshot/enchant callbacks remain untouched. */
object TriggerSkillMobKill : Trigger("skill_mob_kill") {
    private val policy = MobKillXPPolicy()
    private val targetTypes = WeakHashMap<LivingEntity, String>()
    override val description = "Fires after a non-player mob has actually died; text is melee or ranged."
    override val categories = setOf("combat")
    override val parameters = setOf(TriggerParameter.PLAYER, TriggerParameter.VICTIM,
        TriggerParameter.EVENT, TriggerParameter.LOCATION, TriggerParameter.VALUE,
        TriggerParameter.TEXT, TriggerParameter.PROJECTILE)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun captureTarget(event: EntityDamageEvent) {
        val victim = event.entity as? LivingEntity ?: return
        if (victim is Player || victim.hasMetadata("NPC")) return
        targetTypes[victim] = targetType(victim)
    }

    private fun targetType(victim: LivingEntity): String =
        (Entities.getEntity(victim) as? CustomEntity)?.key?.toString()
            ?: "minecraft:${victim.type.name.lowercase()}"

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun handle(event: EntityDeathEvent) {
        val victim = event.entity
        if (victim is Player || victim.hasMetadata("NPC")) return
        val source = event.damageSource
        val direct = source.directEntity
        // AbstractArrow includes normal/spectral arrows and thrown tridents.
        val projectile = direct as? AbstractArrow
        val method = offensiveSkillMethod(direct is Projectile, projectile != null) ?: return
        val player = (source.causingEntity as? Player)
            ?: (projectile?.shooter as? Player)
            ?: victim.killer ?: return
        if (player.hasMetadata("NPC")) return
        val value = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        if (!value.isFinite() || value <= 0) return
        val location = victim.location.clone()
        val targetType = targetTypes.remove(victim) ?: targetType(victim)
        // Recheck cancellation after every listener, including later MONITOR handlers,
        // and refuse revived entities. No XP is given from a damage/forced-kill event.
        plugin.scheduler.runLater(1) {
            if (!policy.accept(victim.uniqueId, event.isCancelled, victim.isDead, victim.health,
                    victim is Player, victim.hasMetadata("NPC"), player.isOnline)) return@runLater
            dispatch(player.toDispatcher(), TriggerData(player = player, victim = victim,
                event = SkillMobKillContext(event, targetType), location = location, value = value, text = method,
                projectile = projectile))
        }
    }
}
