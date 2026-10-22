package com.willfp.ecoskills.gui.menus

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*

class EquipmentSummaryTest {
    @Test fun previewClonesEquippedItemsAndKeepsEmptySlotsExplicitWithoutMutatingInventory() {
        val player = mock(Player::class.java)
        val inventory = mock(PlayerInventory::class.java)
        val helmet = mock(ItemStack::class.java)
        val helmetCopy = mock(ItemStack::class.java)
        val empty = mock(ItemStack::class.java)
        `when`(player.inventory).thenReturn(inventory)
        `when`(inventory.helmet).thenReturn(helmet)
        `when`(inventory.leggings).thenReturn(empty)
        val solid = mock(Material::class.java)
        `when`(solid.isAir).thenReturn(false)
        `when`(helmet.type).thenReturn(solid)
        `when`(helmet.clone()).thenReturn(helmetCopy)
        val air = mock(Material::class.java)
        `when`(air.isAir).thenReturn(true)
        `when`(empty.type).thenReturn(air)
        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            val result = readEquipmentPreview(player)
            assertSame(helmetCopy, result[SummarySlot.HELMET])
            assertNotSame(helmet, result[SummarySlot.HELMET])
            assertNull(result[SummarySlot.CHESTPLATE])
            assertNull(result[SummarySlot.LEGGINGS])
            assertNull(result[SummarySlot.BOOTS])
            verify(helmet, never()).setItemMeta(any(ItemMeta::class.java))
            verify(empty, never()).clone()
            verify(inventory, never()).setHelmet(any(ItemStack::class.java))
            verify(inventory, never()).setChestplate(any(ItemStack::class.java))
            verify(inventory, never()).setLeggings(any(ItemStack::class.java))
            verify(inventory, never()).setBoots(any(ItemStack::class.java))
        }
    }
    @Test fun anotherConsultationReadsEquipmentAgainWithoutRetainingThePreviousPlayerState() {
        val player = mock(Player::class.java)
        val inventory = mock(PlayerInventory::class.java)
        val old = mock(ItemStack::class.java)
        val copy = mock(ItemStack::class.java)
        `when`(player.inventory).thenReturn(inventory)
        `when`(inventory.helmet).thenReturn(old, null)
        val solid = mock(Material::class.java)
        `when`(solid.isAir).thenReturn(false)
        `when`(old.type).thenReturn(solid)
        `when`(old.clone()).thenReturn(copy)
        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            assertSame(copy, readEquipmentPreview(player)[SummarySlot.HELMET])
            assertNull(readEquipmentPreview(player)[SummarySlot.HELMET])
        }
    }
    @Test fun asyncEquipmentReadIsRejectedBeforeAccessingThePlayer() {
        val player = mock(Player::class.java)
        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(false)
            assertThrows(IllegalStateException::class.java) { readEquipmentPreview(player) }
            verifyNoInteractions(player)
        }
    }
    @Test fun layoutHasNoOverlappingControlsAndKeepsEquipmentLeftCategoriesRightNavigationBelow() {
        val entries = SummarySlot.entries
        assertEquals(entries.size, entries.map { it.row to it.column }.toSet().size)
        assertTrue(entries.all { it.row in 1..6 && it.column in 1..9 })
        assertTrue(listOf(SummarySlot.HELMET, SummarySlot.CHESTPLATE, SummarySlot.LEGGINGS,
            SummarySlot.BOOTS, SummarySlot.PET).all { it.column < 5 })
        assertTrue(listOf(SummarySlot.COMBAT, SummarySlot.SURVIVAL, SummarySlot.MANA,
            SummarySlot.GATHERING, SummarySlot.XP).all { it.column > 5 })
        assertTrue(listOf(SummarySlot.BACK, SummarySlot.CLOSE, SummarySlot.REFRESH).all { it.row == 6 })
        assertEquals(5, SummarySlot.CLOSE.column)
    }
}
