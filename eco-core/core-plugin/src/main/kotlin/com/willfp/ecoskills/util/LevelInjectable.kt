package com.willfp.ecoskills.util

import com.willfp.eco.core.placeholder.InjectablePlaceholder
import com.willfp.eco.core.placeholder.PlaceholderInjectable
import com.willfp.eco.core.placeholder.StaticPlaceholder

class LevelInjectable private constructor(level: String) : PlaceholderInjectable {
    constructor(level: Int) : this(level.toString())
    constructor(level: Double) : this(com.willfp.ecoskills.stats.statValueText(level))
    private val placeholders = listOf(
        StaticPlaceholder(
            "level"
        ) { level }
    )

    override fun getPlaceholderInjections(): List<InjectablePlaceholder> {
        return placeholders
    }

    override fun addInjectablePlaceholder(p0: Iterable<InjectablePlaceholder>) {
        return
    }

    override fun clearInjectedPlaceholders() {
        return
    }
}
