package com.willfp.ecoskills.enchanting

import com.willfp.eco.core.Eco
import com.willfp.eco.core.data.keys.PersistentDataKey
import com.willfp.eco.core.data.keys.PersistentDataKeyType
import com.willfp.ecoskills.EcoSkillsPlugin
import com.willfp.ecoskills.api.enchanting.EnchantingXPProvider
import com.willfp.ecoskills.api.event.PlayerSkillLevelUpEvent
import com.willfp.ecoskills.api.event.PlayerSkillXPGainEvent
import com.willfp.ecoskills.effects.Effects
import com.willfp.ecoskills.enchanting.persistence.*
import com.willfp.ecoskills.skills.Skills
import com.willfp.ecoskills.skills.skillXPMultiplier
import com.willfp.ecoskills.skills.isInDisabledWorld
import com.willfp.ecoskills.stats.Stats
import com.willfp.ecoskills.stats.invalidateModifiedStats
import com.willfp.libreforge.EmptyProvidedHolder
import com.willfp.libreforge.toDispatcher
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.ServicePriority
import java.util.UUID

/** Lifecycle is configuration-gated; no API/class presence creates a source confirmation. */
object EnchantingRuntime : Listener {
    private var provider: DurableRuntimeProvider? = null
    @JvmStatic fun enable(plugin: EcoSkillsPlugin) {
        runCatching { enableConfigured(plugin) }.onFailure { plugin.logger.severe("Durable XP configuration unavailable: ${it.javaClass.simpleName}") }
    }
    private fun enableConfigured(plugin: EcoSkillsPlugin) {
        if (!plugin.configYml.getBool("enchanting-purchase-xp.enabled")) return
        check(provider == null)
        require(!plugin.isUsingLocalStorage) { "Durable XP cannot fence locally stored EcoSkills profiles" }
        val scope = plugin.configYml.getString("enchanting-purchase-xp.scope")
        val skill = Skills.getByID("enchanting") ?: error("Native enchanting skill absent")
        val access = Eco.get().profileMutationAccess ?: error("eco mutation capability absent")
        require(access.supported()) { "Durable XP requires the initialized native MySQL handler" }
        require(skill.maxLevel in 1..1024)
        val requirements = DoubleArray(skill.maxLevel) { skill.getXPRequired(it) }
        require(requirements.all { it.isFinite() && it > 0 })
        val rewardRules = skill.durableRewardRules()
        val keys = LinkedHashMap<PersistentDataKey<*>, Class<out Number>>()
        for (nativeSkill in Skills.values()) {
            keys[nativeSkill.getObservationKey()] = Int::class.javaObjectType
            keys[nativeSkill.getObservationXPKey()] = Double::class.javaObjectType
        }
        for (levellable in Stats.values() union Effects.values()) keys[levellable.getObservationKey()] = Int::class.javaObjectType
        require(keys.keys.none { it.isLocal })
        val byName = keys.keys.associateBy { it.key.toString() }
        val host = object : DurableRuntimeProvider.Host {
            override fun isMain() = Bukkit.isPrimaryThread()
            override fun executeMain(task: Runnable) {
                if (!plugin.isEnabled) throw IllegalStateException("EcoSkills disabled")
                if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
            }
            override fun access() = access
            override fun purchaseOwner() = Bukkit.getPluginManager().getPlugin("vEnchanting")
            override fun consumerOwner() = Bukkit.getPluginManager().getPlugin("vPets")
            override fun online(player: UUID) = Bukkit.getPlayer(player)?.isOnline == true
            override fun onlinePlayers() = Bukkit.getOnlinePlayers().map { it.uniqueId }
            override fun keys(): Map<PersistentDataKey<*>, Class<out Number>> = keys
            override fun gateway(prefix: String) = JdbcNativeEnchantingGateway(prefix,skill.getObservationKey().key.toString(),skill.startLevel,
                skill.getObservationXPKey().key.toString(),0.0,rewardRules,keys.mapKeys { it.key.key.toString() })
            override fun maxLevel() = skill.maxLevel
            override fun requirement(level: Int) = requirements[level]
            override fun publish(snapshot: DurableEnchantingStore.NativeSnapshot) {
                check(Bukkit.isPrimaryThread())
                // Mutation admission, NOT observation: confirmed SQL absence uses the actual registered native default.
                // EcoPlayerProfile construction only allocates the cache object; it performs no read/default insertion.
                val profile = Eco.get().loadPlayerProfile(snapshot.fence().player())
                val before = profile.peekCachedValues(keys.keys)
                val values = keys.keys.associateWith { snapshot.values()[it.key.toString()] ?: it.defaultValue as Number }.mapValues { it.value as Any }
                val changedStats = Stats.values().filter { before[it.getObservationKey()] != values[it.getObservationKey()] }.toSet()
                val effectsChanged = Effects.values().any { before[it.getObservationKey()] != values[it.getObservationKey()] }
                access.publish(snapshot.fence().player(),keys.keys,values)
                if (changedStats.isNotEmpty() || effectsChanged) Bukkit.getPlayer(snapshot.fence().player())?.let { invalidateModifiedStats(it, changedStats) }
            }
            override fun modify(player: UUID, baseXP: Double): DurableRuntimeProvider.Modified {
                val current = Bukkit.getPlayer(player) ?: error("Original player offline")
                if (current.isInDisabledWorld || current.gameMode == GameMode.CREATIVE || current.gameMode == GameMode.SPECTATOR ||
                    !skill.conditions.areMet(current.toDispatcher(), EmptyProvidedHolder)) return DurableRuntimeProvider.Modified(0.0,true)
                val event = PlayerSkillXPGainEvent(current,skill,baseXP * current.skillXPMultiplier)
                Bukkit.getPluginManager().callEvent(event)
                return DurableRuntimeProvider.Modified(event.gainedXP,event.isCancelled)
            }
            override fun levelCallback(delivery: DurableEnchantingStore.Delivery) {
                val current = Bukkit.getPlayer(delivery.player()) ?: error("Original callback session absent")
                Bukkit.getPluginManager().callEvent(PlayerSkillLevelUpEvent(current,skill,delivery.level()))
                skill.notifyLevelUpEffects(current,delivery.level()) // Built-in rewards already committed in SQL.
            }
            override fun review(player: UUID, reason: String) { plugin.logger.warning("Durable XP reconciliation: $player $reason") }
        }
        val runtime = DurableRuntimeProvider(host,scope)
        provider = runtime
        Bukkit.getPluginManager().registerEvents(this,plugin)
        runtime.initialize().whenComplete { _, failure ->
            if (failure != null) plugin.logger.severe("Durable XP backend initialization failed; provider remains unavailable: ${failure.javaClass.simpleName}")
            else host.executeMain { Bukkit.getServicesManager().register(EnchantingXPProvider::class.java,runtime,plugin,ServicePriority.Normal) }
        }
    }
    @JvmStatic fun reload() { provider?.configurationChanged() }
    @JvmStatic fun disable(plugin: EcoSkillsPlugin) { provider?.let { Bukkit.getServicesManager().unregister(EnchantingXPProvider::class.java,it); it.close() }; provider = null }
    @EventHandler fun participantDisabled(event: org.bukkit.event.server.PluginDisableEvent) { provider?.participantDisabled(event.plugin) }
    @EventHandler(priority = EventPriority.LOWEST) fun join(event: PlayerJoinEvent) { provider?.joined(event.player.uniqueId) }
    @EventHandler(priority = EventPriority.MONITOR) fun quit(event: PlayerQuitEvent) { provider?.retire(event.player.uniqueId) }
}
