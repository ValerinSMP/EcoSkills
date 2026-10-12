package com.willfp.ecoskills.stats

import com.willfp.ecoskills.api.modifiers.ModifierOperation
import com.willfp.ecoskills.api.modifiers.StatModifier
import com.willfp.eco.core.config.interfaces.Config
import com.willfp.eco.core.placeholder.PlayerStaticPlaceholder
import com.willfp.eco.util.formatEco
import com.willfp.ecoskills.api.getStatValue
import java.math.BigDecimal
import java.math.RoundingMode

/** Same native ADD-then-MULTIPLY order, without truncation or intermediate rounding. */
internal fun modifiedStatValue(base: Double, modifiers: List<StatModifier>): Double {
    var value = base
    for (modifier in modifiers) if (modifier.operation == ModifierOperation.ADD) value += modifier.modifier
    for (modifier in modifiers) if (modifier.operation == ModifierOperation.MULTIPLY) value *= modifier.modifier
    return value
}

/** Round-trippable numeric text for expressions and presentation, without a needless .0. */
internal fun statValueText(value: Double): String =
    if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else value.toString()

/** Player-facing text only; native expressions keep statValueText and the full Double. */
internal fun statDisplayText(value: Double): String =
    if (value.isFinite()) BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString() else value.toString()

/** Preserve legacy integral holder keys; fractional keys are exact IEEE-754 identities. */
internal fun fractionalStatKey(value: Double): String =
    "decimal_" + java.lang.Long.toUnsignedString(java.lang.Double.doubleToLongBits(value), 16)

/** Expression-local injections override visual PAPI formatting without rewriting YAML. */
internal fun Config.injectPreciseStatExpressions(resolveStat: (String) -> Stat? = { Stats.getByID(it) }) {
    val identifiers = linkedSetOf<String>()
    val pattern = Regex("%ecoskills_([a-z0-9_]+)%")
    fun scan(value: Any?) {
        when (value) {
            is String -> pattern.findAll(value).forEach { identifiers.add(it.groupValues[1]) }
            is Config -> value.toMap().values.forEach { scan(it) }
            is Map<*, *> -> value.values.forEach { scan(it) }
            is Iterable<*> -> value.forEach { scan(it) }
        }
    }
    scan(toMap())
    addInjectablePlaceholder(identifiers.map { id ->
        PlayerStaticPlaceholder("ecoskills_$id") { player ->
            val stat = resolveStat(id)
            if (stat != null) statValueText(player.getStatValue(stat))
            // Non-stat placeholders (e.g. mana_limit) must use native resolution, not color-only formatting.
            else "%ecoskills_$id%".formatEco(player, formatPlaceholders = true)
        }
    })
}
