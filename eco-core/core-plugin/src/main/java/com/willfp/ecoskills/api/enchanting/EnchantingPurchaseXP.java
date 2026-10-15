package com.willfp.ecoskills.api.enchanting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/** Pure calculation only. No XP grant, profile, cache, receipt storage or Bukkit access. */
public final class EnchantingPurchaseXP {
    public static final double DAILY_LIMIT = 500_000.0;
    public static final String FORMULA_VERSION = "enchanting-purchase/1";
    private EnchantingPurchaseXP() {}
    public static double effectiveLevels(EnchantingPurchaseEvidence purchase) {
        Objects.requireNonNull(purchase);
        return switch (purchase.mode()) {
            case VANILLA_XP, VANILLA_OVERLEVEL_XP_SHARDS -> purchase.vanillaLevelsConsumed();
            case CUSTOM_SHARDS, CUSTOM_GEMS -> 6.0 * (purchase.toTier() - purchase.fromTier());
        };
    }
    /** Base only: native legitimate modifiers still belong to the future grant provider. Never enchant_item x3. */
    public static double baseXP(EnchantingPurchaseEvidence purchase) {
        return 3.5 * StrictMath.pow(effectiveLevels(purchase), 1.5);
    }
    /** Proposed boundary: after legitimate modifiers; inputs and committed daily usage must be provider-owned. */
    public static double limitedFinalXP(double committedToday, double modifiedFinalXP) {
        if (!Double.isFinite(committedToday) || committedToday < 0 ||
            !Double.isFinite(modifiedFinalXP) || modifiedFinalXP < 0)
            throw new IllegalArgumentException("Daily usage and final XP must be finite and nonnegative");
        return Math.min(modifiedFinalXP, Math.max(0.0, DAILY_LIMIT - committedToday));
    }
    /** Proposed shared reset boundary, not server-local timezone. No daily persistence is performed here. */
    public static LocalDate utcDay(Instant instant) {
        return Objects.requireNonNull(instant).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
