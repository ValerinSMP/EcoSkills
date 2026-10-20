package com.willfp.ecoskills.libreforge

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.ecoskills.api.event.PlayerSkillLevelUpEvent
import com.willfp.ecoskills.skills.Skills
import com.willfp.libreforge.ArgType
import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.ProvidedHolder
import com.willfp.libreforge.arguments
import com.willfp.libreforge.conditions.Condition
import com.willfp.libreforge.get
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority

object ConditionHasSkillLevel : Condition<NoCompileData>("has_skill_level"), com.willfp.libreforge.conditions.CacheOnlyCondition<NoCompileData> {
    override val description = "Passes when the player's level in the given skill is at or above the minimum."

    override val categories = setOf("player")

    override val arguments = arguments {
        require(
            "skill",
            "You must specify the skill!",
            description = "The skill to check the level of.",
            type = ArgType.STRING
        )
        require(
            "level",
            "You must specify the skill level!",
            description = "The minimum skill level required.",
            type = ArgType.EXPRESSION
        )
    }

    override fun isMet(
        dispatcher: Dispatcher<*>,
        config: Config,
        holder: ProvidedHolder,
        compileData: NoCompileData
    ): Boolean {
        val player = dispatcher.get<Player>() ?: return false

        val skillId = config.getString("skill").lowercase()
        val skill = Skills.getByID(skillId)
        if (skill == null) {
            return false
        }

        val actual = com.willfp.ecoskills.skills.SkillRequirementViews.peek(player.uniqueId, skill) ?: return false
        val required = config.getIntFromExpression("level", player)
        return actual >= required
    }

    override fun isMetCached(dispatcher: Dispatcher<*>, config: Config, holder: ProvidedHolder, compileData: NoCompileData): Boolean? {
        val player = dispatcher.get<Player>() ?: return false
        val skill = Skills.getByID(config.getString("skill").lowercase()) ?: return false
        val actual = com.willfp.ecoskills.skills.SkillRequirementViews.peek(player.uniqueId, skill) ?: return false
        // Literal requirements are cache-only on packet threads; dynamic expressions retain native MAIN evaluation.
        val required = config.getIntOrNull("level") ?: return null
        return actual >= required
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun handle(event: PlayerSkillLevelUpEvent) {
        com.willfp.ecoskills.skills.SkillRequirementViews.changed(event.player.uniqueId, event.skill.id)
    }
}
