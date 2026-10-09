package com.willfp.ecoskills.libreforge

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.ecoskills.skills.matchesSkillMobType
import com.willfp.libreforge.ArgType
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.filters.Filter
import com.willfp.libreforge.triggers.TriggerData

object FilterSkillMobType : Filter<NoCompileData, Collection<String>>("skill_mob_type") {
    override val description = "Matches the captured entity ID from a validated skill mob death."
    override val categories = setOf("entity")
    override val valueType = ArgType.STRING_LIST
    override fun getValue(config: Config, data: TriggerData?, key: String) = config.getStrings(key)
    override fun isMet(data: TriggerData, value: Collection<String>, compileData: NoCompileData): Boolean {
        val context = data.event as? SkillMobKillContext ?: return false
        return matchesSkillMobType(context.targetType, value)
    }
}
