package com.willfp.ecoskills.stats

import com.willfp.ecoskills.api.modifiers.ModifierOperation
import com.willfp.ecoskills.api.modifiers.StatModifier
import java.math.BigDecimal

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

/** Preserve legacy integral holder keys; fractional keys are exact IEEE-754 identities. */
internal fun fractionalStatKey(value: Double): String =
    "decimal_" + java.lang.Long.toUnsignedString(java.lang.Double.doubleToLongBits(value), 16)
