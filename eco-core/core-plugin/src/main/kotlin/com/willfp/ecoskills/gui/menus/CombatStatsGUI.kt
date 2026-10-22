package com.willfp.ecoskills.gui.menus

import com.willfp.eco.core.config.ConfigType
import com.willfp.eco.core.config.ExtendableConfig
import com.willfp.eco.core.gui.menu
import com.willfp.eco.core.gui.slot
import com.willfp.eco.core.items.builder.ItemStackBuilder
import com.willfp.eco.core.placeholder.context.placeholderContext
import com.willfp.eco.util.evaluateExpression
import com.willfp.ecoskills.api.observation.ObservationRequest
import com.willfp.ecoskills.api.observation.ObservationService
import com.willfp.ecoskills.magic.MagicTypes
import com.willfp.ecoskills.magic.peekObservedMagic
import com.willfp.ecoskills.plugin
import com.willfp.ecoskills.stats.Stats
import com.willfp.ecoskills.stats.statDisplayText
import com.willfp.libreforge.observation.CachedEffects
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import java.util.UUID

internal data class CombatResources(val health: Double, val maximumHealth: Double?, val mana: Int?, val maximumMana: Double?)
internal fun readCombatResources(player: Player, mana: () -> Int?, maximumMana: () -> Double?,
    maximumHealth: () -> Double? = { player.getAttribute(Attribute.MAX_HEALTH)?.value }): CombatResources {
    check(org.bukkit.Bukkit.isPrimaryThread())
    val current = mana()
    return CombatResources(player.health, maximumHealth(),
        current, if (current != null) maximumMana() else null)
}

private class CombatMenuYml : ExtendableConfig("combat-menu", true, plugin, plugin.javaClass, "", ConfigType.YAML)

/** Small native-menu pilot. Current resources are read on every navigation, never from lore cache. */
object CombatStatsGUI {
    private val config by lazy { CombatMenuYml() }
    private data class Breakdown(val session: Player, val at: Long, val signature: String, val lines: List<String>)
    private val cache = LinkedHashMap<UUID, Breakdown>()
    fun isEnabled() = config.getBool("enabled")
    private fun text(key: String) = config.getFormattedString("text.$key")
    private fun number(value: Double?) = value?.takeIf { it.isFinite() }?.let(::statDisplayText) ?: text("unavailable")
    private fun lines(key: String) = config.getFormattedStrings("text.$key")
    internal fun clearCache() { cache.clear() }
    private fun item(material: Material, name: String, lore: List<String>) = ItemStackBuilder(material)
        .setDisplayName(name).addLoreLines(lore).build()

    private fun resources(player: Player): List<String> {
        val mana = MagicTypes.getByID("mana")
        val state = readCombatResources(player,
            { mana?.takeIf { com.willfp.ecoskills.api.observation.ObservationSessions.hasCurrentMagic(player, it.id) }
                ?.let { peekObservedMagic(player.uniqueId, it) } },
            { mana?.getLimit(player)?.toDouble() })
        return listOf(text("health").replace("%current%", number(state.health)).replace("%max%", number(state.maximumHealth)),
            text("mana").replace("%current%", number(state.mana?.toDouble())).replace("%max%", number(state.maximumMana)))
    }

    fun openSummary(player: Player) = render(player, "summary", null)
    private fun openCombat(player: Player) = render(player, "combat", null)
    private fun openDetail(player: Player, id: String) = render(player, "detail", id)

    private fun render(player: Player, screen: String, id: String?) {
        check(org.bukkit.Bukkit.isPrimaryThread())
        if (!player.isOnline) return
        val current = resources(player)
        val filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", emptyList())
        val view = menu(4) {
            title = config.getString("titles.$screen")
            for (row in 1..4) for (column in 1..9) setSlot(row, column,
                slot(filler))
            setSlot(1, 5, slot(item(Material.PLAYER_HEAD, text("character"), current)))
            when (screen) {
                "summary" -> {
                    setSlot(2, 5, slot(item(Material.IRON_SWORD, text("combat"), lines("combat-lore"))) {
                        onLeftClick { _, _ -> openCombat(player) }
                    })
                    setSlot(3, 5, slot(item(Material.BOOK, text("legacy"), emptyList())) {
                        onLeftClick { _, _ -> StatsGUI.openLegacy(player) }
                    })
                }
                "combat" -> for ((column, statId) in listOf(3 to "strength", 5 to "crit_chance", 7 to "crit_damage")) {
                    val stat = Stats.getByID(statId)
                    setSlot(2, column, slot(item(Material.ENCHANTED_BOOK, stat?.name ?: text("unavailable"),
                        listOf(text("view-detail")))) { onLeftClick { _, _ -> openDetail(player, statId) } })
                }
                "detail" -> {
                    val stat = Stats.getByID(id!!)
                    val lore = if (stat == null) listOf(text("unavailable")) else breakdown(player, id)
                    val contributionStart = lore.indexOf(text("contributions"))
                    val conditionalStart = lore.indexOf(text("conditional"))
                    if (contributionStart >= 0 && conditionalStart > contributionStart) {
                        setSlot(2, 3, slot(item(Material.PAPER, text("contributions"), lore.subList(contributionStart + 1, conditionalStart))))
                        setSlot(2, 5, slot(item(Material.ENCHANTED_BOOK, stat?.name ?: text("unavailable"), lore.take(contributionStart))))
                        setSlot(2, 7, slot(item(Material.IRON_SWORD, text("conditional"), lore.drop(conditionalStart + 1))))
                    } else setSlot(2, 5, slot(item(Material.ENCHANTED_BOOK, stat?.name ?: text("unavailable"), lore)))
                }
            }
            setSlot(4, 3, slot(item(Material.SPECTRAL_ARROW, text("back"), emptyList())) {
                onLeftClick { _, _ -> when(screen) { "detail" -> openCombat(player); "combat" -> openSummary(player); else -> SkillsGUI.open(player) } }
            })
            setSlot(4, 5, slot(item(Material.BARRIER, text("close"), emptyList())) {
                onLeftClick { _, _ -> player.closeInventory() }
            })
            setSlot(4, 7, slot(item(Material.CLOCK, text("refresh"), lines("refresh-lore"))) {
                onLeftClick { _, _ -> cache.remove(player.uniqueId); render(player, screen, id) }
            })
        }
        view.open(player)
    }

    private fun breakdown(player: Player, id: String): List<String> {
        val observation = ObservationService.cached(player.uniqueId,
            ObservationRequest(setOf(ObservationRequest.Section.STATS, ObservationRequest.Section.MODIFIERS))).asMap()
        val entries = ((observation["stats"] as? Map<*, *>)?.get("items") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val observed = entries.firstOrNull { it["id"] == id }
        fun numeric(key: String): Double? {
            val field = observed?.get(key) as? Map<*, *> ?: return null
            if (field["status"] != "available") return null
            return (field["value"] as? Number)?.toDouble()
        }
        val effective = numeric("modified")
        val modifiers = ((observation["modifiers"] as? Map<*, *>)?.get("items") as? List<*>)
            ?.filterIsInstance<Map<*, *>>()?.filter { it["statId"] == id } ?: emptyList()
        val active = CachedEffects.getCachedObservation(player.uniqueId)
        val relevant = active.entries.filter { it.sourceNamespace != "ecoskills" &&
            it.triggers.any { trigger -> trigger in setOf("melee_attack", "bow_attack", "trident_attack", "headshot") } }
        val signature = listOf(Stats.getByID(id), id, numeric("base"), effective, modifiers, active.available, active.pending, active.truncated,
            relevant.map { listOf(it.sourceNamespace, it.sourceId, it.effectId, it.triggers, it.worlds, it.literalMultiplier, it.hasEventRestrictions) }).toString()
        val now = System.currentTimeMillis()
        cache[player.uniqueId]?.takeIf { it.session === player && it.signature == signature && now - it.at < 2000 }?.let { return it.lines }
        val stat = Stats.getByID(id) ?: return listOf(text("unavailable"))
        val lore = arrayListOf(text("points").replace("%value%", number(effective)), " ")
        lore += lines("explanation.$id")
        if (effective != null) {
            // Evaluate the native configured expressions with the stat's existing precise injections.
            val owner = if (id == "crit_damage") Stats.getByID("crit_chance") else stat
            val kind = if (id == "strength") "damage_multiplier" else "make_skill_crit"
            val effect = owner?.config?.getSubsections("effects")?.firstOrNull { it.getString("id") == kind }
            val key = if (id == "crit_chance") "chance" else "multiplier"
            val expr = effect?.getSubsection("args")?.getStringOrNull(key)
            val nativePresent = active.entries.any { it.sourceNamespace == "ecoskills" && it.sourceId.startsWith("${owner?.id}_") && it.effectId == kind }
            val value = expr?.takeIf { nativePresent && !active.pending }?.let { runCatching { evaluateExpression(it, placeholderContext(player = player, injectable = owner!!.config)) }.getOrNull() }
            lore += text(if (key == "chance") "threshold" else "native-multiplier").replace("%value%", number(value))
            lore += text("native-scope")
        }
        lore += listOf(" ", text("contributions"), text("base").replace("%value%", number(numeric("base"))))
        for (mod in modifiers.take(8)) {
            val amount = (mod["amount"] as? Number)?.toDouble()
            lore += text(if (mod["operation"] == "MULTIPLY") "multiply-contribution" else "add-contribution")
                .replace("%value%", number(amount))
        }
        lore += text("source-unavailable")
        if (modifiers.size > 8) lore += text("partial")
        lore += listOf(" ", text("conditional"))
        if (!active.available) lore += text("unavailable")
        else {
            if (active.pending) lore += text("pending")
            for (effect in relevant.filter { it.effectId == "damage_multiplier" }.take(5)) {
                val ownerName = config.getFormattedString("sources.${effect.sourceNamespace}")
                    .takeIf { it.isNotBlank() } ?: text("unknown-source")
                val triggers = effect.triggers.joinToString(", ") { trigger ->
                    config.getString("triggers.$trigger").takeIf { it.isNotBlank() } ?: text("unknown-trigger")
                }.ifBlank { text("unknown-trigger") }
                lore += text("conditional-entry").replace("%source%", ownerName)
                    .replace("%value%", number(effect.literalMultiplier)).replace("%trigger%", triggers)
                if (effect.worlds.isNotEmpty()) lore += text("world-scope").replace("%world%", effect.worlds.joinToString(", ") {
                    config.getString("worlds.$it").takeIf { name -> name.isNotBlank() } ?: text("unknown-world")
                })
                if (effect.hasEventRestrictions) lore += text("event-restrictions")
            }
            if (relevant.isEmpty()) lore += text("no-known-bonuses")
            if (active.truncated || relevant.size > 5) lore += text("partial")
        }
        lore += lines("limits")
        cache.entries.removeIf { now - it.value.at > 2000 || !it.value.session.isOnline }
        if (cache.size >= 128) cache.remove(cache.keys.first())
        cache[player.uniqueId] = Breakdown(player, now, signature, lore.toList())
        return lore
    }
}
