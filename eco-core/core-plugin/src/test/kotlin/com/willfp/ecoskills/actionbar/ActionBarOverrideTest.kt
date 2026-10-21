package com.willfp.ecoskills.actionbar

import com.willfp.eco.core.Eco
import com.willfp.eco.core.config.base.ConfigYml
import com.willfp.eco.core.data.PlayerProfile
import com.willfp.eco.core.placeholder.context.PlaceholderContext
import com.willfp.ecoskills.EcoSkillsPlugin
import com.willfp.ecoskills.api.showTemporaryActionBar
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.UUID

class ActionBarOverrideTest {
 @Suppress("UNCHECKED_CAST")
 private fun map(name:String)=Class.forName("com.willfp.ecoskills.actionbar.ActionBarHandlerKt")
  .getDeclaredField(name).apply{isAccessible=true}.get(null) as MutableMap<UUID,Long>
 @Test fun `temporary override restores current data and respects external pause and disabled native bar`() {
  val plugin=mock(EcoSkillsPlugin::class.java);val config=mock(ConfigYml::class.java)
  `when`(plugin.configYml).thenReturn(config);`when`(plugin.isEnabled).thenReturn(true)
  `when`(config.getStrings("persistent-action-bar.disabled-in-worlds")).thenReturn(emptyList())
  `when`(config.getBool("persistent-action-bar.enabled")).thenReturn(true)
  Class.forName("com.willfp.ecoskills.EcoSkillsPluginKt").getDeclaredField("plugin").apply{isAccessible=true}.set(null,plugin)
  val player=mock(Player::class.java);val spigot=mock(Player.Spigot::class.java);val world=mock(World::class.java)
  val id=UUID.randomUUID();`when`(player.uniqueId).thenReturn(id);`when`(player.isOnline).thenReturn(true)
  `when`(player.spigot()).thenReturn(spigot);`when`(player.world).thenReturn(world);`when`(world.name).thenReturn("world")
  `when`(player.gameMode).thenReturn(GameMode.SURVIVAL);`when`(player.health).thenReturn(10.0)
  val eco=mock(Eco::class.java);val profile=mock(PlayerProfile::class.java);`when`(eco.loadPlayerProfile(id)).thenReturn(profile)
  `when`(eco.createNamespacedKey(anyString(),anyString())).thenAnswer { org.bukkit.NamespacedKey(it.getArgument<String>(0),it.getArgument<String>(1)) }
  val scheduler=mock(BukkitScheduler::class.java);val scheduled=mutableListOf<Runnable>()
  `when`(scheduler.runTaskLater(eq(plugin),any(Runnable::class.java),anyLong())).thenAnswer {
   assertEquals(20L,it.getArgument<Long>(2));scheduled+=it.getArgument<Runnable>(1);mock(BukkitTask::class.java)
  }
  mockStatic(Eco::class.java).use { em ->
   em.`when`<Eco>{Eco.get()}.thenReturn(eco)
   mockStatic(Bukkit::class.java).use { bm ->
    bm.`when`<Boolean>{Bukkit.isPrimaryThread()}.thenReturn(true)
    bm.`when`<BukkitScheduler>{Bukkit.getScheduler()}.thenReturn(scheduler)
    bm.`when`<Player>{Bukkit.getPlayer(id)}.thenReturn(player)
    `when`(profile.read(getActionBarObservationKey())).thenReturn(true)
    `when`(config.getFormattedString(eq("persistent-action-bar.format"),any(PlaceholderContext::class.java))).thenAnswer { "health=${player.health}" }
    map("blacklist")[id]=System.currentTimeMillis()+2700
    assertTrue(showTemporaryActionBar(player,"Disponible en 19 s",1000))
    player.pausePersistentActionBar() // Own packet: must not extend to the old 2700 ms pause.
    assertTrue(map("blacklist")[id]!!-System.currentTimeMillis() <= 1000L)
    `when`(player.health).thenReturn(7.0);map("blacklist")[id]=0L
    scheduled.removeAt(0).run()
    val sends=mockingDetails(spigot).invocations.filter { it.method.name=="sendMessage" }
    assertEquals(2,sends.size)
    val components=sends.last().rawArguments[1] as Array<net.md_5.bungee.api.chat.BaseComponent>
    assertTrue(components.joinToString(""){it.toPlainText()}.contains("health=7.0"))
    // A newer external actionbar retains its own pause, so our delayed callback must not erase it.
    assertTrue(showTemporaryActionBar(player,"another notice",1000));map("whitelist")[id]=0L
    player.pausePersistentActionBar();val count=mockingDetails(spigot).invocations.size
    scheduled.removeAt(0).run();assertEquals(count,mockingDetails(spigot).invocations.size)
    // Native bar disabled: clear our temporary text after the deadline instead of enabling it.
    map("blacklist").remove(id);`when`(config.getBool("persistent-action-bar.enabled")).thenReturn(false)
    assertTrue(showTemporaryActionBar(player,"last notice",1000));map("blacklist")[id]=0L;scheduled.removeAt(0).run()
    val last=mockingDetails(spigot).invocations.last().rawArguments[1] as Array<net.md_5.bungee.api.chat.BaseComponent>
    assertEquals("",last.joinToString(""){it.toPlainText()})
    assertTrue(showTemporaryActionBar(player,"old connection",1000));map("blacklist")[id]=0L
    bm.`when`<Player>{Bukkit.getPlayer(id)}.thenReturn(mock(Player::class.java))
    val beforeReconnect=mockingDetails(spigot).invocations.size;scheduled.removeAt(0).run()
    assertEquals(beforeReconnect,mockingDetails(spigot).invocations.size)
    bm.`when`<Boolean>{Bukkit.isPrimaryThread()}.thenReturn(false)
    assertThrows(IllegalStateException::class.java){showTemporaryActionBar(player,"bad thread",1000)}
   }
  }
 }
}
