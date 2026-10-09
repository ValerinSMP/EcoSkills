package com.willfp.ecoskills.libreforge

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.ArgType
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.effects.impl.DamageMultiplierBypass
import com.willfp.libreforge.filters.Filter
import com.willfp.libreforge.triggers.TriggerData

object FilterIsAbility : Filter<NoCompileData, Boolean>("is_ability") {
    override val valueType = ArgType.BOOLEAN

    override fun getValue(config: Config, data: TriggerData?, key: String) = config.getBool(key)

    override fun isMet(data: TriggerData, value: Boolean, compileData: NoCompileData): Boolean {
        return DamageMultiplierBypass.isAbility == value
    }
}
