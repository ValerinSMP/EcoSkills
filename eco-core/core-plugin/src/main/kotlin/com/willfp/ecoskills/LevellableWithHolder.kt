package com.willfp.ecoskills

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.Holder
import com.willfp.libreforge.ViolationContext
import com.willfp.libreforge.conditions.ConditionList
import com.willfp.libreforge.conditions.Conditions
import com.willfp.libreforge.effects.EffectList
import com.willfp.libreforge.effects.Effects

abstract class LevellableWithHolder(
    id: String,
    config: Config
) : Levellable(id, config) {
    private val levels = mutableMapOf<Int, LevelHolder>()

    // Bounded cache: fractional pet values must not create an unbounded lifetime map.
    private val fractionalLevels = object : LinkedHashMap<Double, Holder>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Double, Holder>?) = size > 512
    }

    private val effects: EffectList
    private val conditions: ConditionList

    init {
        effects = Effects.compile(
            config.getSubsections("effects"),
            ViolationContext(plugin, id)
        )

        conditions = Conditions.compile(
            config.getSubsections("conditions"),
            ViolationContext(plugin, id)
        )
    }

    fun getLevelHolder(level: Int): Holder? = if (level == 0) null else levels.getOrPut(level) {
        LevelHolder(level, conditions, effects)
    }

    fun getLevelHolder(level: Double): Holder? {
        if (!level.isFinite() || level == 0.0) return null
        if (level >= Int.MIN_VALUE && level <= Int.MAX_VALUE && level == level.toInt().toDouble()) {
            return getLevelHolder(level.toInt())
        }
        return fractionalLevels.getOrPut(level) {
            object : Holder {
                override val id = plugin.createNamespacedKey("${this@LevellableWithHolder.id}_${com.willfp.ecoskills.stats.fractionalStatKey(level)}")
                override val conditions = this@LevellableWithHolder.conditions
                override val effects = this@LevellableWithHolder.effects
            }
        }
    }

    private inner class LevelHolder(
        level: Int,
        override val conditions: ConditionList,
        override val effects: EffectList
    ) : Holder {
        override val id = plugin.createNamespacedKey("${this@LevellableWithHolder.id}_$level")
    }
}
