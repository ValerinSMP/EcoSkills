package com.willfp.ecoskills.gui.menus

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito

class CombatResourcesTest {
    @Test fun navigationReadsFreshResourcesEvenWhenBreakdownIsCached() {
        val player = Mockito.mock(Player::class.java)
        Mockito.`when`(player.health).thenReturn(20.0, 13.0)
        var mana = 80
        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            assertEquals(CombatResources(20.0, 40.0, 80, 100.0), readCombatResources(player, { mana }, { 100.0 }, { 40.0 }))
            mana = 42
            assertEquals(CombatResources(13.0, 40.0, 42, 100.0), readCombatResources(player, { mana }, { 100.0 }, { 40.0 }))
            Mockito.verify(player, Mockito.never()).setHealth(Mockito.anyDouble())
        }
    }
    @Test fun absentManaStaysUnavailableWithoutCallingItsLimitOrInitialisingIt() {
        val player = Mockito.mock(Player::class.java)
        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            val value = readCombatResources(player, { null }, { error("must not initialise mana") }, { null })
            assertNull(value.mana); assertNull(value.maximumMana)
        }
    }
    @Test fun liveReadsRejectAsyncBeforeTouchingPlayerOrMana() {
        val player = Mockito.mock(Player::class.java)
        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(false)
            assertThrows(IllegalStateException::class.java) { readCombatResources(player, { error("wrong thread") }, { null }) }
            Mockito.verifyNoInteractions(player)
        }
    }
}
