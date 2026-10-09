package com.willfp.ecoskills.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MobKillXPPolicyTest {
    @Test fun `supported projectiles are ranged and harmless projectiles do not award XP`() {
        assertEquals("ranged", offensiveSkillMethod(true, true))
        assertNull(offensiveSkillMethod(true, false))
        assertEquals("melee", offensiveSkillMethod(false, false))
    }
    @Test fun `captured Mythic identity does not fall back to vanilla reward`() {
        assertTrue(matchesSkillMobType("mythicmobs:sculk_sentinel", listOf("mythicmobs:sculk_sentinel")))
        assertFalse(matchesSkillMobType("mythicmobs:sculk_sentinel", listOf("minecraft:wolf")))
        assertFalse(matchesSkillMobType(null, listOf("minecraft:wolf")))
    }
    @Test fun `real death accepted once and another mob accepted independently`() {
        val policy = MobKillXPPolicy()
        val id = UUID.randomUUID()
        assertTrue(policy.accept(id, false, true, 0.0, false, false, true))
        assertFalse(policy.accept(id, false, true, 0.0, false, false, true))
        assertTrue(policy.accept(UUID.randomUUID(), false, true, 0.0, false, false, true))
    }
    @Test fun `cancelled distant immune and revived mobs never give kill XP`() {
        val policy = MobKillXPPolicy()
        for ((cancelled, dead, health) in listOf(Triple(true, true, 0.0),
                Triple(false, false, 100.0), Triple(false, false, 1.0),
                Triple(false, true, 5.0), Triple(false, true, Double.NaN))) {
            assertFalse(policy.accept(UUID.randomUUID(), cancelled, dead, health, false, false, true))
        }
    }
    @Test fun `players NPCs and disconnected killers excluded`() {
        val policy = MobKillXPPolicy()
        assertFalse(policy.accept(UUID.randomUUID(), false, true, 0.0, true, false, true))
        assertFalse(policy.accept(UUID.randomUUID(), false, true, 0.0, false, true, true))
        assertFalse(policy.accept(UUID.randomUUID(), false, true, 0.0, false, false, false))
    }
    @Test fun `cancelled notification does not consume a later real death`() {
        val policy = MobKillXPPolicy()
        val id = UUID.randomUUID()
        assertFalse(policy.accept(id, true, false, 1.0, false, false, true))
        assertTrue(policy.accept(id, false, true, 0.0, false, false, true))
    }
}
