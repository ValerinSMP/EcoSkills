package com.willfp.ecoskills.stats

import com.willfp.ecoskills.api.*
import com.willfp.ecoskills.api.modifiers.ModifierOperation
import com.willfp.ecoskills.api.modifiers.StatModifier
import com.willfp.ecoskills.util.LevelInjectable
import com.willfp.eco.core.placeholder.StaticPlaceholder
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class StatDecimalsTest {
    private val id = UUID.randomUUID()
    private val player = Mockito.mock(Player::class.java).also {
        Mockito.`when`(it.uniqueId).thenReturn(id)
        Mockito.`when`(it.isOnline).thenReturn(false)
    }
    private fun stat(key: String = "health", base: Int = 100) = Mockito.mock(Stat::class.java).also {
        Mockito.`when`(it.id).thenReturn(key)
        Mockito.`when`(it.getSavedLevel(player)).thenReturn(base)
    }
    private val stat = stat()
    private val changes = mutableListOf<Set<Stat>>()
    private val modifiers = StatModifiers(player).also { it.onChanged = { stats -> changes.add(stats) } }
    private fun modifier(value: Double, operation: ModifierOperation = ModifierOperation.ADD,
                         uuid: UUID = UUID.randomUUID(), target: Stat = stat) = StatModifier(uuid, target, value, operation)

    @Test fun `common level one bonus remains 002 and legacy remains 100`() {
        modifiers.add(modifier(0.02))
        assertEquals(100.02, modifiers.getStatValue(stat), 1e-12)
        assertEquals(0.02, modifiers.getBonusStatValue(stat), 1e-12)
        assertEquals(100, modifiers.getModifiedValue(stat))
        assertEquals(0, modifiers.getBonusStatLevel(stat))
        assertEquals(100.02, player.getStatValue(stat), 1e-12)
        assertEquals(0.02, player.getBonusStatValue(stat), 1e-12)
        assertEquals(100, player.getStatLevel(stat))
        assertEquals(0, player.getBonusStatLevel(stat))
    }

    @Test fun `native ADD then MULTIPLY ordering ignores insertion interleaving`() {
        modifiers.add(modifier(1.5, ModifierOperation.MULTIPLY))
        modifiers.add(modifier(0.02))
        modifiers.add(modifier(0.5, ModifierOperation.MULTIPLY))
        modifiers.add(modifier(0.03))
        assertEquals((100.0 + 0.02 + 0.03) * 1.5 * 0.5, modifiers.getStatValue(stat), 1e-12)
        assertEquals(75, modifiers.getModifiedValue(stat))
    }

    @Test fun `same UUID replacement never accumulates and identical writes do not invalidate`() {
        val uuid = UUID.randomUUID()
        val first = modifier(0.02, uuid = uuid)
        modifiers.add(first); modifiers.add(first)
        assertEquals(1, changes.size)
        modifiers.add(modifier(0.04, uuid = uuid))
        assertEquals(1, modifiers.getModifiers().size)
        assertEquals(100.04, modifiers.getStatValue(stat), 1e-12)
        assertEquals(2, changes.size)
        assertNotNull(modifiers.remove(uuid))
        assertEquals(100.0, modifiers.getStatValue(stat))
        assertEquals(0.0, modifiers.getBonusStatValue(stat))
        assertEquals(3, changes.size)
        assertNull(modifiers.remove(uuid));assertEquals(3, changes.size)
    }

    @Test fun `UUID replacement across stats refreshes old and new without a residual bonus`() {
        val other=stat("strength",20)
        val uuid=UUID.randomUUID()
        modifiers.add(modifier(0.02, uuid=uuid))
        modifiers.add(modifier(0.03, uuid=uuid,target=other))
        assertEquals(setOf(stat,other),changes.last())
        assertEquals(100.0,modifiers.getStatValue(stat))
        assertEquals(20.03,modifiers.getStatValue(other),1e-12)
    }

    @Test fun `reload stat registry replacement preserves modifiers by stat id`() {
        modifiers.add(modifier(0.02))
        val reloaded=stat("health",100)
        assertEquals(100.02,modifiers.getStatValue(reloaded),1e-12)
        assertEquals(1,modifiers.getModifiers(reloaded).size)
    }

    @Test fun `quit and new session with same UUID cannot inherit transient bonuses`() {
        modifiers.add(modifier(0.02))
        val quit=Mockito.mock(PlayerQuitEvent::class.java)
        Mockito.`when`(quit.player).thenReturn(player)
        StatModifierListener.onLeave(quit)
        val next=Mockito.mock(Player::class.java)
        Mockito.`when`(next.uniqueId).thenReturn(id)
        Mockito.`when`(stat.getSavedLevel(next)).thenReturn(100)
        assertEquals(100.0,next.getStatValue(stat))
        assertEquals(0.0,next.getBonusStatValue(stat))
        assertTrue(StatModifiers(next).getModifiers().isEmpty())
    }

    @Test fun `offline API reads base only and never returns session modifier bonus`() {
        val offline=Mockito.mock(OfflinePlayer::class.java)
        Mockito.`when`(stat.getSavedLevel(offline)).thenReturn(100)
        assertEquals(100.0,offline.getStatValue(stat))
        assertEquals(0.0,offline.getBonusStatValue(stat))
        assertEquals(100,offline.getStatLevel(stat))
    }

    @Test fun `fraction-only stat at base zero remains nonzero and legacy remains zero`() {
        val zero=stat("strength",0)
        modifiers.add(modifier(0.02,target=zero))
        assertEquals(0.02,modifiers.getStatValue(zero))
        assertEquals(0,modifiers.getModifiedValue(zero))
    }

    @Test fun `negative legacy truncation and multiply remain unchanged`() {
        modifiers.add(modifier(-100.02))
        assertEquals(0,modifiers.getModifiedValue(stat))
        assertEquals(-100,modifiers.getBonusStatLevel(stat))
        modifiers.add(modifier(2.0,ModifierOperation.MULTIPLY))
        assertEquals(-0.04,modifiers.getStatValue(stat),1e-12)
    }

    @Test fun `small fractions survive without intermediate rounding`() {
        modifiers.add(modifier(0.00000002))
        assertEquals(100.00000002,modifiers.getStatValue(stat),1e-12)
        assertEquals("100.00000002",statValueText(modifiers.getStatValue(stat)))
    }

    @Test fun `description and effect injection exposes exact decimals and legacy integer constructor`() {
        val decimal=LevelInjectable(100.02).placeholderInjections.single() as StaticPlaceholder
        val legacy=LevelInjectable(100).placeholderInjections.single() as StaticPlaceholder
        assertEquals("100.02",decimal.value)
        assertEquals("100",legacy.value)
        assertEquals(100.02,decimal.value.toDouble())
        assertEquals("0.02",statValueText(0.02))
        assertEquals("100",statValueText(100.0))
    }

    @Test fun `nearby decimal holder identities are distinct with no rounded key collision`() {
        assertNotEquals(fractionalStatKey(100.02),fractionalStatKey(100.02000000001))
        assertEquals(fractionalStatKey(100.02),fractionalStatKey(100.02))
        assertFalse(fractionalStatKey(0.02).contains('.'))
    }
}
