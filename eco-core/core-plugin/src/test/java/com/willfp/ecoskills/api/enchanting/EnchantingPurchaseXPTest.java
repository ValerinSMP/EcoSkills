package com.willfp.ecoskills.api.enchanting;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class EnchantingPurchaseXPTest {
    private EnchantingPurchaseEvidence evidence(EnchantingPurchaseEvidence.Mode mode, int from, int to, int spent) {
        return new EnchantingPurchaseEvidence(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "survival", UUID.fromString("00000000-0000-0000-0000-000000000003"),
            "ecoenchants:lifesteal", from, to, mode, spent, "prices/1", "a".repeat(64),
            UUID.fromString("00000000-0000-0000-0000-000000000004"), 1792000000000L);
    }
    @Test void customUpgradeUsesDeltaAndDoesNotApplyTriggerMultiplier() {
        var p=evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,3,4,0);
        assertEquals(6.0,EnchantingPurchaseXP.effectiveLevels(p));
        assertEquals(51.43928459844674,EnchantingPurchaseXP.baseXP(p),1e-12);
        assertEquals(EnchantingPurchaseXP.baseXP(p),EnchantingPurchaseXP.baseXP(evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,3,4,0)));
    }
    @Test void vanillaUsesOnlyRealConsumedLevels() {
        assertEquals(3.5*StrictMath.pow(20,1.5),EnchantingPurchaseXP.baseXP(evidence(EnchantingPurchaseEvidence.Mode.VANILLA_XP,0,5,20)));
        assertEquals(3.5*StrictMath.pow(7,1.5),EnchantingPurchaseXP.baseXP(evidence(EnchantingPurchaseEvidence.Mode.VANILLA_OVERLEVEL_XP_SHARDS,5,6,7)));
    }
    @Test void invalidUpgradeAndConsumptionAreRejected() {
        assertThrows(IllegalArgumentException.class,()->evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,4,4,0));
        assertThrows(IllegalArgumentException.class,()->evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,4,3,0));
        assertThrows(IllegalArgumentException.class,()->evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,0,1,1));
        assertThrows(IllegalArgumentException.class,()->evidence(EnchantingPurchaseEvidence.Mode.VANILLA_XP,0,1,0));
    }
    @Test void identityIsStableAndPayloadConflictIsObservable() {
        var a=evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,3,4,0);
        var repeated=evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,3,4,0);
        var conflict=evidence(EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,3,4,0);
        assertEquals(a.payloadHash(),repeated.payloadHash());assertEquals(a.awardId(),repeated.awardId());
        assertNotEquals(a.payloadHash(),conflict.payloadHash());assertEquals(a.awardId(),conflict.awardId());
        assertEquals(64,a.payloadHash().length());
    }
    @Test void finalCapClipsAfterModifiersAndRejectsNonfiniteInput() {
        assertEquals(1.5,EnchantingPurchaseXP.limitedFinalXP(499998.5,100));
        assertEquals(0,EnchantingPurchaseXP.limitedFinalXP(500000,10));
        assertEquals(0,EnchantingPurchaseXP.limitedFinalXP(500001,10));
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}) {
            assertThrows(IllegalArgumentException.class,()->EnchantingPurchaseXP.limitedFinalXP(bad,10));
            assertThrows(IllegalArgumentException.class,()->EnchantingPurchaseXP.limitedFinalXP(0,bad));
        }
    }
    @Test void dailyBoundaryIsSharedUtcMidnight() {
        assertEquals(LocalDate.of(2026,10,14),EnchantingPurchaseXP.utcDay(Instant.parse("2026-10-14T23:59:59Z")));
        assertEquals(LocalDate.of(2026,10,15),EnchantingPurchaseXP.utcDay(Instant.parse("2026-10-15T00:00:00Z")));
    }
}
