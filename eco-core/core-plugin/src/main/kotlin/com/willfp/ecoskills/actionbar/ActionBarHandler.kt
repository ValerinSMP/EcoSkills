package com.willfp.ecoskills.actionbar

import com.willfp.eco.core.data.keys.PersistentDataKey
import com.willfp.eco.core.data.keys.PersistentDataKeyType
import com.willfp.eco.core.data.profile
import com.willfp.eco.core.placeholder.InjectablePlaceholder
import com.willfp.eco.core.placeholder.PlaceholderInjectable
import com.willfp.eco.core.placeholder.PlayerStaticPlaceholder
import com.willfp.eco.core.placeholder.context.placeholderContext
import com.willfp.eco.util.containsIgnoreCase
import com.willfp.eco.util.namespacedKeyOf
import com.willfp.ecoskills.api.getStatLevel
import com.willfp.ecoskills.plugin
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

private const val ACTION_BAR_DURATION = 2700L
private const val TICK_DURATION = 50L

private val blacklist = mutableMapOf<UUID, Long>()

private val whitelist = mutableMapOf<UUID, Long>()
private val temporaryBars = mutableMapOf<UUID, Pair<Any, BukkitTask>>()

private val actionBarEnabledKey = PersistentDataKey(
    namespacedKeyOf("ecoskills", "actionbar_enabled"),
    PersistentDataKeyType.BOOLEAN,
    true
)

fun getActionBarObservationKey() = actionBarEnabledKey

fun Player.togglePersistentActionBar() {
    this.profile.write(actionBarEnabledKey, !this.profile.read(actionBarEnabledKey))
}

val Player.isPersistentActionBarEnabled: Boolean
    get() = this.profile.read(actionBarEnabledKey)

fun Player.sendCompatibleActionBarMessage(message: String) {
    // Have to use the shit method for compatibility.
    @Suppress("DEPRECATION")
    this.spigot().sendMessage(
        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
        *net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message)
    )
}

fun Player.pausePersistentActionBar() {
    if (isSendingPersistentActionBar) {
        return
    }

    blacklist[this.uniqueId] = System.currentTimeMillis() + ACTION_BAR_DURATION
}

private val Player.isPersistentActionBarPaused: Boolean
    get() {
        val time = blacklist[this.uniqueId] ?: return false
        return time > System.currentTimeMillis()
    }

private fun Player.sendPersistentActionBar(message: String) {
    whitelist[this.uniqueId] = System.currentTimeMillis() + TICK_DURATION
    sendCompatibleActionBarMessage(message)
}

private val Player.isSendingPersistentActionBar: Boolean
    get() {
        val time = whitelist[this.uniqueId] ?: return false
        return time > System.currentTimeMillis()
    }

object ActionBarGamemodeListener : Listener {
    @EventHandler
    fun handle(event: PlayerGameModeChangeEvent) {
        if (event.newGameMode in setOf(GameMode.CREATIVE, GameMode.SPECTATOR)) {
            event.player.sendPersistentActionBar("")
        }
    }
}

object ActionBarHandler {
    private var tickingTask: BukkitTask? = null

    private val disabledWorlds = plugin.configYml
        .getStrings("persistent-action-bar.disabled-in-worlds")

    private fun trySendMessage(player: Player): Boolean {
        if (player.isPersistentActionBarPaused || !player.isPersistentActionBarEnabled) {
            return false
        }

        if (plugin.configYml.getBool("persistent-action-bar.require-permission")) {
            if (!player.hasPermission("ecoskills.enable-persistent-action-bar")) {
                return false
            }
        }

        if (disabledWorlds.containsIgnoreCase(player.world.name)) {
            return false
        }

        if (player.gameMode in setOf(GameMode.CREATIVE, GameMode.SPECTATOR)) {
            return false
        }

        if (plugin.configYml.getBool("persistent-action-bar.scale-health")) {
            player.isHealthScaled = true
            player.healthScale = 20.0
        }

        player.sendPersistentActionBar(
            plugin.configYml
                .getFormattedString(
                    "persistent-action-bar.format", placeholderContext(
                        player = player,
                        injectable = PlayerHealthInjectable
                    )
                )
        )
        return true
    }

    /** Main-thread explicit override; packet compatibility sees this as our own send. */
    fun showTemporaryActionBar(player: Player, message: String, durationMillis: Long): Boolean {
        check(Bukkit.isPrimaryThread()) { "Temporary actionbar requires the main thread" }
        require(durationMillis in 50L..10000L) { "Duration must be 50..10000 ms" }
        if (!player.isOnline || !plugin.isEnabled) return false
        temporaryBars.remove(player.uniqueId)?.second?.cancel()
        val token=Any()
        blacklist[player.uniqueId]=System.currentTimeMillis()+durationMillis
        player.sendPersistentActionBar(message)
        val restore=Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (temporaryBars[player.uniqueId]?.first !== token) return@Runnable
            temporaryBars.remove(player.uniqueId)
            if (Bukkit.getPlayer(player.uniqueId) !== player || !player.isOnline || player.isPersistentActionBarPaused) return@Runnable
            blacklist.remove(player.uniqueId)
            // Re-evaluate settings, permissions, world, health and mana; never replay captured text.
            if (!plugin.configYml.getBool("persistent-action-bar.enabled") || !trySendMessage(player))
                player.sendPersistentActionBar("")
        }, (durationMillis+49L)/50L)
        temporaryBars[player.uniqueId]=token to restore
        return true
    }

    internal fun startTicking() {
        tickingTask?.cancel()
        PlayerHealthInjectable.refreshStatInjections()
        val interval = plugin.configYml.getInt("persistent-action-bar.interval", 10).coerceAtLeast(1).toLong()
        tickingTask = plugin.scheduler.runTimer(interval, interval) {
            for (player in Bukkit.getOnlinePlayers()) {
                trySendMessage(player)
            }
        }
    }

    object PlayerHealthInjectable : PlaceholderInjectable {
        private val healthInjections = listOf(
            PlayerStaticPlaceholder(
                "health"
            ) { it.health.toInt().toString() },
            PlayerStaticPlaceholder(
                "max_health"
            ) { it.getAttribute(Attribute.MAX_HEALTH)?.value?.toInt()?.toString() ?: "20" },
        )

        private var injections: List<InjectablePlaceholder> = healthInjections

        internal fun refreshStatInjections(stats: Collection<com.willfp.ecoskills.stats.Stat> = com.willfp.ecoskills.stats.Stats.values()) {
            injections = healthInjections + stats.map { stat ->
                PlayerStaticPlaceholder("ecoskills_${stat.id}") { player ->
                    player.getStatLevel(stat).toString()
                }
            }
        }

        override fun getPlaceholderInjections(): List<InjectablePlaceholder> {
            return injections
        }

        override fun addInjectablePlaceholder(p0: Iterable<InjectablePlaceholder>) {
            return
        }

        override fun clearInjectedPlaceholders() {
            return
        }
    }
}

object HealthScaleDisabler : Listener {
    @EventHandler
    fun handle(event: PlayerJoinEvent) {
        if (!plugin.configYml.getBool("persistent-action-bar.scale-health")) {
            event.player.isHealthScaled = false
        }
    }
}
