package com.willfp.ecoskills.skills

import com.willfp.eco.core.Eco
import com.willfp.eco.core.data.observation.ProfileCacheUpdates
import com.willfp.eco.core.display.DisplayViewUpdates
import com.willfp.ecoskills.EcoSkillsPlugin
import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.updateEffects
import org.bukkit.Bukkit
import org.bukkit.event.*
import org.bukkit.event.player.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Cache notifications contain no levels; flush always reads the current session, never stale payloads. */
object SkillRequirementViews : Listener {
 private val active=ConcurrentHashMap.newKeySet<UUID>()
 private val keys=AtomicReference<Map<com.willfp.eco.core.data.keys.PersistentDataKey<*>,String>>(emptyMap())
 private val pending=ViewUpdateQueue(2048,
  { task -> Bukkit.getScheduler().runTask(owner,task) },
  { uuid,dependencies ->
   if(!stopped)Bukkit.getPlayer(uuid)?.takeIf{it.isOnline}?.let { player ->
    player.toDispatcher().updateEffects()
    DisplayViewUpdates.refresh(player,dependencies)
   }
  })
 @Volatile private var stopped=true
 private var subscription:AutoCloseable?=null
 private lateinit var owner:EcoSkillsPlugin
 fun peek(uuid:UUID,skill:Skill):Int? {
  val key=skill.getObservationKey()
  val snapshot=Eco.get().profileObservationAccess?.peek(uuid,setOf(key)) ?: return null
  return (snapshot.values()[key] as? Int)?.takeIf { snapshot.loaded() && it>=0 }
 }
 fun install(plugin:EcoSkillsPlugin){
  owner=plugin
  plugin.onEnable {
   stopped=false
   Bukkit.getPluginManager().registerEvents(this,plugin)
   rebuild()
   subscription=ProfileCacheUpdates.listen { change ->
    val current=keys.get()
    val ids=if(change.unloaded())current.values.toSet() else change.keys().mapNotNull{current[it]}.toSet()
    if(ids.isNotEmpty())request(change.uuid(),ids)
   }
   Bukkit.getOnlinePlayers().forEach { active.add(it.uniqueId);request(it.uniqueId,keys.get().values.toSet()) }
  }
  plugin.onReload { rebuild();Bukkit.getOnlinePlayers().forEach { active.add(it.uniqueId);request(it.uniqueId,keys.get().values.toSet()) } }
  plugin.onDisable { stopped=true;subscription?.close();subscription=null;pending.clear();active.clear();keys.set(emptyMap());HandlerList.unregisterAll(this) }
 }
 private fun rebuild(){keys.set(Skills.values().associate{it.getObservationKey() to "ecoskills:${it.id}"})}
 fun changed(uuid:UUID,skillId:String){request(uuid,setOf("ecoskills:$skillId"))}
 private fun request(uuid:UUID,ids:Set<String>){if(!stopped && uuid in active)pending.request(uuid,ids)}
 @EventHandler fun join(event:PlayerJoinEvent){active.add(event.player.uniqueId);request(event.player.uniqueId,keys.get().values.toSet())}
 @EventHandler fun quit(event:PlayerQuitEvent){active.remove(event.player.uniqueId);pending.retire(event.player.uniqueId)}
 @EventHandler fun world(event:PlayerChangedWorldEvent){request(event.player.uniqueId,keys.get().values.toSet())}
}
