@file:JvmName("EcoSkillsAPI")

package com.willfp.ecoskills.api

import com.willfp.ecoskills.api.modifiers.StatModifier
import com.willfp.ecoskills.effects.Effect
import com.willfp.ecoskills.effects.Effects
import com.willfp.ecoskills.effects.effects
import com.willfp.ecoskills.magic.MagicType
import com.willfp.ecoskills.magic.magic
import com.willfp.ecoskills.skills.Skill
import com.willfp.ecoskills.skills.SkillLevel
import com.willfp.ecoskills.skills.Skills
import com.willfp.ecoskills.skills.skills
import com.willfp.ecoskills.stats.Stat
import com.willfp.ecoskills.stats.Stats
import com.willfp.ecoskills.stats.statModifiers
import com.willfp.ecoskills.stats.stats
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

/** Read-only observation facade. Cached methods require the main thread. */
fun getCachedObservation(uuid: UUID) = com.willfp.ecoskills.api.observation.ObservationService.cached(uuid)
fun getCachedObservation(uuid: UUID, request: com.willfp.ecoskills.api.observation.ObservationRequest) =
    com.willfp.ecoskills.api.observation.ObservationService.cached(uuid, request)
fun getCachedSnapshot(player: Player): Map<String, Any?> {
    check(org.bukkit.Bukkit.isPrimaryThread()) { "Cached observation requires the main thread" }
    return getCachedSnapshot(player.uniqueId)
}
fun getCachedSnapshot(uuid: UUID): Map<String, Any?> =
    com.willfp.ecoskills.api.observation.ObservationService.legacy(uuid)
fun getStoredObservationAsync(uuid: UUID): java.util.concurrent.CompletionStage<com.willfp.ecoskills.api.observation.PlayerObservation> = com.willfp.ecoskills.api.observation.ObservationService.stored(uuid, com.willfp.ecoskills.api.observation.ObservationRequest.all())
fun getStoredObservationAsync(uuid: UUID, request: com.willfp.ecoskills.api.observation.ObservationRequest): java.util.concurrent.CompletionStage<com.willfp.ecoskills.api.observation.PlayerObservation> = com.willfp.ecoskills.api.observation.ObservationService.stored(uuid, request)
fun getStoredObservationsAsync(uuids: Set<UUID>): java.util.concurrent.CompletionStage<Map<UUID, com.willfp.ecoskills.api.observation.PlayerObservation>> = com.willfp.ecoskills.api.observation.ObservationService.storedBatch(uuids, com.willfp.ecoskills.api.observation.ObservationRequest.all())
fun getStoredObservationsAsync(uuids: Set<UUID>, request: com.willfp.ecoskills.api.observation.ObservationRequest): java.util.concurrent.CompletionStage<Map<UUID, com.willfp.ecoskills.api.observation.PlayerObservation>> = com.willfp.ecoskills.api.observation.ObservationService.storedBatch(uuids, request)
fun getObservationDefinitions() = com.willfp.ecoskills.api.observation.ObservationService.definitions()
fun getObservationCapabilities() = com.willfp.ecoskills.api.observation.ObservationService.capabilities()

/*

Skills

 */

fun OfflinePlayer.resetSkills() {
    for (skill in Skills.values()) {
        this.skills.reset(skill)
    }
    for (stat in Stats.values()) {
        this.stats.reset(stat)
    }
    for (effect in Effects.values()) {
        this.effects.reset(effect)
    }
}

fun OfflinePlayer.resetSkill(skill: Skill) =
    this.skills.reset(skill)

fun OfflinePlayer.getSkillXP(skill: Skill): Double =
    this.skills[skill].xp

fun OfflinePlayer.gainSkillXP(skill: Skill, xp: Double): Unit =
    this.skills.gainXP(skill, xp)

fun OfflinePlayer.giveSkillXP(skill: Skill, xp: Double): Unit =
    this.skills.giveXP(skill, xp)

fun OfflinePlayer.getRequiredXP(skill: Skill) =
    skill.getXPRequired(this.getSkillLevel(skill))

fun OfflinePlayer.getFormattedRequiredXP(skill: Skill) =
    skill.getFormattedXPRequired(this.getSkillLevel(skill))

fun OfflinePlayer.getSkillProgress(skill: Skill): Double {
    val currentXP = getSkillXP(skill)
    val requiredXP = getRequiredXP(skill)

    return currentXP / requiredXP
}

fun OfflinePlayer.getSkillLevel(skill: Skill): Int =
    this.skills[skill].level

fun OfflinePlayer.setSkillLevel(skill: Skill, level: Int) =
    this.skills.set(skill, SkillLevel(level, 0.0))

val OfflinePlayer.totalSkillLevel: Int
    get() = Skills.values().sumOf { this.getSkillLevel(it) }

val OfflinePlayer.averageSkillLevel: Double
    get() = this.totalSkillLevel.toDouble() / Skills.values().size


/*

Stats

 */

fun OfflinePlayer.getBaseStatLevel(stat: Stat): Int =
    this.stats[stat]

fun OfflinePlayer.setBaseStatLevel(stat: Stat, value: Int) =
    this.stats.set(stat, value)

fun OfflinePlayer.giveBaseStatLevel(stat: Stat, amount: Int) {
    this.stats[stat] += amount
}

/** Effective stat including transient modifiers, without legacy Int truncation. */
fun OfflinePlayer.getStatValue(stat: Stat): Double =
    if (this is Player) this.statModifiers.getStatValue(stat) else this.getBaseStatLevel(stat).toDouble()

/** Effective value minus the persisted base; modifiers are session-only. */
fun OfflinePlayer.getBonusStatValue(stat: Stat): Double =
    if (this is Player) this.statModifiers.getBonusStatValue(stat) else 0.0

fun OfflinePlayer.getStatLevel(stat: Stat): Int =
    if (this is Player) this.statModifiers.getModifiedValue(stat) else this.getBaseStatLevel(stat)

val OfflinePlayer.statModifiers: List<StatModifier>
    get() = if (this is Player) this.statModifiers.getModifiers() else emptyList()

fun OfflinePlayer.getStatModifiers(stat: Stat): List<StatModifier> =
    if (this is Player) this.statModifiers.getModifiers(stat) else emptyList()

fun OfflinePlayer.getBonusStatLevel(stat: Stat): Int =
    if (this is Player) this.statModifiers.getBonusStatLevel(stat) else 0

fun Player.addStatModifier(statModifier: StatModifier): Unit =
    this.statModifiers.add(statModifier)

fun Player.removeStatModifier(uuid: UUID): StatModifier? =
    this.statModifiers.remove(uuid)

fun Player.getStatModifier(uuid: UUID): StatModifier? =
    this.statModifiers.getModifier(uuid)

/*

Effects

 */

fun OfflinePlayer.getEffectLevel(effect: Effect): Int =
    this.effects[effect]

/*

Mana

 */

fun Player.getMagic(type: MagicType): Int =
    this.magic[type]

fun Player.setMagic(type: MagicType, amount: Int): Unit =
    this.magic.set(type, amount)

fun Player.getMaxMagic(type: MagicType): Int =
    type.getLimit(this)
