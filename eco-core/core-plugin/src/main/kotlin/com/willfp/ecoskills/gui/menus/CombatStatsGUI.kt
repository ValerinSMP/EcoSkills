package com.willfp.ecoskills.gui.menus

import com.willfp.eco.core.config.ConfigType
import com.willfp.eco.core.config.ExtendableConfig
import com.willfp.eco.core.gui.menu
import com.willfp.eco.core.gui.slot
import com.willfp.eco.core.gui.slot.FillerMask
import com.willfp.eco.core.items.builder.ItemStackBuilder
import com.willfp.ecoskills.api.observation.PureExpression
import kotlin.math.roundToInt
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
import org.bukkit.inventory.ItemStack
import java.util.UUID

internal data class CombatResources(val health: Double, val maximumHealth: Double?, val mana: Int?, val maximumMana: Double?)
internal fun readCombatResources(player: Player, mana: () -> Int?, maximumMana: () -> Double?,
    maximumHealth: () -> Double? = { player.getAttribute(Attribute.MAX_HEALTH)?.value }): CombatResources {
    check(org.bukkit.Bukkit.isPrimaryThread())
    val current = mana()
    return CombatResources(player.health, maximumHealth(),
        current, if (current != null) maximumMana() else null)
}

/** Fixed native menu layout; every entry is display-only unless it has an explicit navigation handler. */
internal enum class SummarySlot(val row: Int, val column: Int) {
    CHARACTER(2, 2), HELMET(2, 3), CHESTPLATE(3, 3), LEGGINGS(4, 3), BOOTS(5, 3),
    PET(4, 2), COMBAT(2, 6), SURVIVAL(2, 8), MANA(4, 8), GATHERING(2, 7), XP(4, 6),
    FORAGING(3, 6), FISHING(3, 7), MISC(3, 8), EFFECTS(6, 7),
    BACK(6, 4), CLOSE(6, 5), REFRESH(6, 6)
}

internal fun readEquipmentPreview(player: Player): Map<SummarySlot, ItemStack?> {
    check(org.bukkit.Bukkit.isPrimaryThread())
    val equipment = player.inventory
    return mapOf(SummarySlot.HELMET to equipment.helmet, SummarySlot.CHESTPLATE to equipment.chestplate,
        SummarySlot.LEGGINGS to equipment.leggings, SummarySlot.BOOTS to equipment.boots)
        .mapValues { (_, item) -> item?.takeUnless { it.type.isAir }?.clone() }
}

private class CombatMenuYml : ExtendableConfig("combat-menu", true, plugin, plugin.javaClass, "", ConfigType.YAML)

/** Small native-menu pilot. Current resources are read on every navigation, never from lore cache. */
object CombatStatsGUI {
    private val config by lazy { CombatMenuYml() }
    private data class Breakdown(val session: Player, val at: Long, val signature: String, val lines: List<String>)
    private val cache = LinkedHashMap<UUID, Breakdown>()
    fun isEnabled() = config.getBool("enabled")
    private fun text(key: String) = config.getFormattedString("text.$key")
    private fun number(value: Double?) = value?.takeIf { it.isFinite() }?.let { statDisplayText(it).replace('.', ',') } ?: text("unavailable")
    private fun lines(key: String) = config.getFormattedStrings("text.$key")
    private val formulas = LinkedHashMap<String, PureExpression?>()
    internal fun clearCache() { cache.clear(); formulas.clear() }
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
    private fun openCombat(player: Player) = openCategory(player, "combat")
    private fun openDetail(player: Player, id: String) = render(player, "detail", id)

    private fun categoryIds() = config.getSubsections("catalog.categories").map { it.getString("id") }
    private fun categoryConfig(id: String) = config.getSubsections("catalog.categories").first { it.getString("id") == id }
    private fun categoryStats(id: String) = categoryConfig(id).getStrings("stats").filter { Stats.getByID(it) != null && config.has("catalog.stats.$it") }
    private fun statName(id: String) = config.getFormattedString("catalog.stats.$id.name")
    private fun statIcon(id: String) = Material.valueOf(config.getString("catalog.stats.$id.icon"))
    private fun categoryIcon(id: String) = Material.valueOf(categoryConfig(id).getString("icon"))
    private fun categoryName(id: String) = categoryConfig(id).getFormattedString("name")
    private fun categoryForStat(id: String) = categoryIds().firstOrNull { id in categoryStats(it) } ?: "combat"
    private fun observedStats(player: Player): List<Map<*, *>> = statRows(ObservationService.cached(player.uniqueId,
        ObservationRequest(setOf(ObservationRequest.Section.STATS))).asMap())
    private fun statRows(observation: Map<*, *>): List<Map<*, *>> =
        ((observation["stats"] as? Map<*, *>)?.get("items") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
    private fun observedNumber(rows: List<Map<*, *>>, id: String, key: String = "modified"): Double? {
        val field = rows.firstOrNull { it["id"] == id }?.get(key) as? Map<*, *> ?: return null
        return (field["value"] as? Number)?.toDouble()?.takeIf { field["status"] == "available" && it.isFinite() }
    }
    private fun openCategory(player: Player, id: String) = render(player, "category", id)
    private fun categoryLore(player: Player, id: String, rows: List<Map<*, *>>): List<String> =
        categoryConfig(id).getFormattedStrings("description") + listOf(" ") + categoryStats(id).map { stat ->
            text("stat-summary").replace("%name%", statName(stat)).replace("%value%", number(observedNumber(rows, stat)))
        } + listOf(" ", text("view-detail"))

    /** Read the configured expression, not a duplicate gameplay formula or a universal damage total. */
    private fun metricLore(player: Player, id: String, rows: List<Map<*, *>>): List<String> {
        val definition = config.getSubsection("catalog.stats.$id")
        val ownerId = definition.getStringOrNull("owner") ?: id
        val owner = Stats.getByID(ownerId) ?: return listOf(text("unavailable"))
        if (observedNumber(rows, id) == null || observedNumber(rows, ownerId) == null) return listOf(text("unavailable"))
        if (observedNumber(rows, ownerId) == 0.0) return listOf(text("native-zero"))
        val effect = owner.config.getSubsections("effects").firstOrNull { it.getString("id") == definition.getString("effect") }
            ?: return listOf(text("native-unavailable"))
        val expression = effect.getSubsection("args").getStringOrNull(definition.getString("argument"))
            ?: return listOf(text("native-unavailable"))
        // Reuse the observation API's pure native parser: cached inputs only, no Player/PAPI/profile resolution.
        if (formulas.size >= 128 && !formulas.containsKey(expression)) formulas.remove(formulas.keys.first())
        if (!formulas.containsKey(expression)) formulas[expression] = PureExpression.prepare(expression, com.willfp.eco.core.Eco.get().javaClass.classLoader)
        val prepared = formulas[expression] ?: return listOf(text("native-unavailable"))
        val inputs = HashMap<String, Double>()
        for (variable in prepared.variables()) {
            val statId = if (variable == "%level%") ownerId else variable.removePrefix("%ecoskills_").removeSuffix("%")
            val value = observedNumber(rows, statId) ?: return listOf(text("native-unavailable"))
            inputs[variable] = value
        }
        val value = prepared.evaluate(inputs) ?: return listOf(text("native-unavailable"))
        return definition.getFormattedStrings("result").map {
            it.replace("%value%", number(value)).replace("%percent%", number((value - 1) * 100))
                .replace("%chance%", number(value.coerceIn(0.0, 100.0)))
                .replace("%rounded%", number(value.roundToInt().toDouble()))
        } + listOf(text("formula-scope"))
    }
    private fun statLore(player: Player, id: String, rows: List<Map<*, *>>): List<String> =
        listOf(text("points").replace("%value%", number(observedNumber(rows, id))), " ") +
            config.getFormattedStrings("catalog.stats.$id.description") + listOf(" ") + metricLore(player, id, rows)

    private fun renderSummary(player: Player, current: List<String>) {
        val equipment = readEquipmentPreview(player)
        // vPets remains authoritative. Its current public command opens the owner menu;
        // private PetService fields are deliberately not inspected to guess selected/active state.
        val pets = org.bukkit.Bukkit.getPluginCommand("vpets")?.takeIf {
            it.plugin.name == "vPets" && it.plugin.isEnabled
        }
        val view = menu(6) {
            title = config.getString("titles.summary")
            // Background must not compete with content in the native MIDDLE layer.
            setMask(FillerMask(Material.BLACK_STAINED_GLASS_PANE, *Array(6) { "111111111" }))
            fun put(at: SummarySlot, icon: ItemStack) = setSlot(at.row, at.column, slot(icon))
            put(SummarySlot.CHARACTER, item(Material.PLAYER_HEAD,
                text("character").replace("%player%", player.name), current))
            for ((at, equipped) in equipment) {
                val id = at.name.lowercase()
                val icon = if (equipped != null) ItemStackBuilder(equipped)
                    .addLoreLines(listOf(" ", text("equipment.read-only"))).build()
                else item(Material.valueOf("CHAINMAIL_${at.name}"), text("equipment.empty-$id"), lines("equipment.empty-lore"))
                put(at, icon)
            }
            val petIcon = item(Material.BONE, text("pet.title"),
                lines("pet.unavailable") + if (pets != null) listOf(" ", text("pet.open")) else emptyList())
            if (pets == null) put(SummarySlot.PET, petIcon)
            else setSlot(SummarySlot.PET.row, SummarySlot.PET.column, slot(petIcon) {
                onLeftClick { _, _ ->
                    if (pets.plugin.isEnabled) {
                        player.closeInventory()
                        pets.execute(player, pets.name, emptyArray())
                    }
                }
            })
            val observed = observedStats(player)
            for (id in categoryIds()) {
                val category = categoryConfig(id)
                val at = SummarySlot.valueOf(category.getString("slot"))
                val available = categoryStats(id).isNotEmpty()
                val icon = item(categoryIcon(id), categoryName(id), if (available) categoryLore(player, id, observed) else lines("coming-soon"))
                if (available) setSlot(at.row, at.column, slot(icon) { onLeftClick { _, _ -> openCategory(player, id) } })
                else put(at, icon)
            }
            // XP aggregation remains outside this stat-only increment; never fabricate a total.
            put(SummarySlot.XP, item(Material.EXPERIENCE_BOTTLE, text("categories.xp"), lines("coming-soon")))
            setSlot(SummarySlot.EFFECTS.row, SummarySlot.EFFECTS.column,
                slot(item(Material.POTION, text("potion-effects"), listOf(text("effect-count").replace("%value%", player.activePotionEffects.size.toString()), " ", text("view-detail")))) {
                    onLeftClick { _, _ -> render(player, "potions", null) }
                })
            setSlot(SummarySlot.BACK.row, SummarySlot.BACK.column,
                slot(item(Material.SPECTRAL_ARROW, text("back"), emptyList())) {
                    onLeftClick { _, _ -> SkillsGUI.open(player) }
                })
            setSlot(SummarySlot.CLOSE.row, SummarySlot.CLOSE.column,
                slot(item(Material.BARRIER, text("close"), emptyList())) {
                    onLeftClick { _, _ -> player.closeInventory() }
                })
            setSlot(SummarySlot.REFRESH.row, SummarySlot.REFRESH.column,
                slot(item(Material.CLOCK, text("refresh"), lines("refresh-lore"))) {
                    onLeftClick { _, _ -> cache.remove(player.uniqueId); openSummary(player) }
                })
        }
        view.open(player)
    }

    private fun render(player: Player, screen: String, id: String?) {
        check(org.bukkit.Bukkit.isPrimaryThread())
        if (!player.isOnline) return
        val current = resources(player)
        if (screen == "summary") { renderSummary(player, current); return }
        val rows = observedStats(player)
        val view = menu(6) {
            title = when (screen) { "category" -> config.getString("titles.category").replace("%category%", org.bukkit.ChatColor.stripColor(categoryName(id!!)) ?: ""); "detail" -> config.getString("titles.detail"); else -> config.getString("titles.potions") }
            setMask(FillerMask(Material.BLACK_STAINED_GLASS_PANE, *Array(6) { "111111111" }))
            if (screen != "category") setSlot(1, 5, slot(item(Material.PLAYER_HEAD, text("character"), current)))
            when (screen) {
                "category" -> {
                    setSlot(1, 5, slot(item(categoryIcon(id!!), categoryName(id), categoryLore(player, id, rows) + current)))
                    for ((index, statId) in categoryStats(id).take(21).withIndex()) {
                        setSlot(2 + index / 7, 2 + index % 7,
                            slot(item(statIcon(statId), statName(statId), statLore(player, statId, rows) + listOf(" ", text("view-detail")))) {
                                onLeftClick { _, _ -> openDetail(player, statId) }
                            })
                    }
                }
                "potions" -> {
                    val effects = player.activePotionEffects.toList()
                    if (effects.isEmpty()) setSlot(3, 5, slot(item(Material.GLASS_BOTTLE, text("potion-effects"), lines("no-potions"))))
                    if (effects.size > 28) setSlot(5, 9, slot(item(Material.PAPER, text("potion-effects"), lines("potions-partial"))))
                    for ((index, effect) in effects.take(28).withIndex()) {
                        val name = config.getFormattedString("potions.${effect.type.key.key}").takeIf { it.isNotBlank() } ?: text("unknown-potion")
                        val duration = if (effect.duration < 0) text("infinite") else text("seconds").replace("%value%", (effect.duration / 20).toString())
                        setSlot(2 + index / 7, 2 + index % 7, slot(item(Material.POTION, name,
                            listOf(text("potion-level").replace("%value%", (effect.amplifier + 1).toString()), duration))))
                    }
                }
                "detail" -> {
                    val stat = Stats.getByID(id!!)
                    val lore = if (stat == null) listOf(text("unavailable")) else breakdown(player, id)
                    val contributionStart = lore.indexOf(text("contributions"))
                    val conditionalStart = lore.indexOf(text("conditional"))
                    if (contributionStart >= 0 && conditionalStart > contributionStart) {
                        setSlot(2, 3, slot(item(Material.PAPER, text("contributions"), lore.subList(contributionStart + 1, conditionalStart))))
                        setSlot(2, 5, slot(item(Material.ENCHANTED_BOOK, statName(id), lore.take(contributionStart))))
                        setSlot(2, 7, slot(item(Material.IRON_SWORD, text("conditional"), lore.drop(conditionalStart + 1))))
                    } else setSlot(2, 5, slot(item(Material.ENCHANTED_BOOK, statName(id), lore)))
                }
            }
            setSlot(6, 4, slot(item(Material.SPECTRAL_ARROW, text("back"), emptyList())) {
                onLeftClick { _, _ -> when(screen) { "detail" -> openCategory(player, categoryForStat(id!!)); else -> openSummary(player) } }
            })
            setSlot(6, 5, slot(item(Material.BARRIER, text("close"), emptyList())) {
                onLeftClick { _, _ -> player.closeInventory() }
            })
            setSlot(6, 6, slot(item(Material.CLOCK, text("refresh"), lines("refresh-lore"))) {
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
        val relevant = active.entries.filter { categoryForStat(id) == "combat" && it.sourceNamespace != "ecoskills" &&
            it.triggers.any { trigger -> trigger in setOf("melee_attack", "bow_attack", "trident_attack", "headshot") } }
        val signature = listOf(Stats.getByID(id), id, numeric("base"), effective, modifiers, active.available, active.pending, active.truncated,
            relevant.map { listOf(it.sourceNamespace, it.sourceId, it.effectId, it.triggers, it.worlds, it.literalMultiplier, it.hasEventRestrictions) }).toString()
        val now = System.currentTimeMillis()
        cache[player.uniqueId]?.takeIf { it.session === player && it.signature == signature && now - it.at < 2000 }?.let { return it.lines }
        val stat = Stats.getByID(id) ?: return listOf(text("unavailable"))
        val lore = ArrayList(statLore(player, id, entries))
        lore += listOf(" ", text("contributions"), text("base").replace("%value%", number(numeric("base"))))
        for (mod in modifiers.take(8)) {
            val amount = (mod["amount"] as? Number)?.toDouble()
            lore += text(if (mod["operation"] == "MULTIPLY") "multiply-contribution" else "add-contribution")
                .replace("%value%", number(amount))
        }
        lore += text("source-unavailable")
        if (modifiers.size > 8) lore += text("partial")
        lore += listOf(" ", text("conditional"))
        if (categoryForStat(id) != "combat") lore += lines("scope-only")
        else if (!active.available) lore += text("unavailable")
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
        if (categoryForStat(id) == "combat") lore += lines("limits")
        cache.entries.removeIf { now - it.value.at > 2000 || !it.value.session.isOnline }
        if (cache.size >= 128) cache.remove(cache.keys.first())
        cache[player.uniqueId] = Breakdown(player, now, signature, lore.toList())
        return lore
    }
}
