package com.willfp.ecoskills.gui.menus
import com.willfp.ecoskills.api.observation.PureExpression
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import kotlin.math.roundToInt

class StatsReferenceTest {
 private fun yaml(name:String)=YamlConfiguration.loadConfiguration(InputStreamReader(javaClass.getResourceAsStream("/$name")!!,Charsets.UTF_8))
 @Test fun catalogUsesAllTwentyImplementedStatsOnceAndKeepsCategoriesAtNonOverlappingNativeSlots(){
  val cfg=yaml("combat-menu.yml");val cats=cfg.getMapList("catalog.categories")
  val ids=cats.flatMap{it["stats"] as List<*>}.map{it.toString()}
  assertEquals(20,ids.size);assertEquals(20,ids.toSet().size);assertFalse("mana_discount" in ids)
  assertEquals(ids.toSet(),cfg.getConfigurationSection("catalog.stats")!!.getKeys(false))
  val slots=cats.map{SummarySlot.valueOf(it["slot"].toString())}
  assertEquals(slots.size,slots.toSet().size);assertTrue(slots.all{it.column in 6..8&&it.row in 2..4})
  assertTrue(ids.all{cfg.getStringList("catalog.stats.$it.description").isNotEmpty()})
  assertEquals("#FFB000",cfg.getString("catalog.stats.attack_speed.name")!!.substring(1,8))
 }
 private fun evaluate(id:String,arg:String,values:Map<String,Double>):Double? {
  val fixtures=yaml("stats-reference-native.yml");val expr=fixtures.getString("$id.$arg")!!
  return PureExpression.prepare(expr,javaClass.classLoader)!!.evaluate(values)
 }
 @Test fun displayedFormulasUseTheNativeParserAndFullPrecisionInputsRatherThanPointsAsPercentages(){
  assertEquals(1.25,evaluate("strength","multiplier",mapOf("%level%" to 100.0)))
  assertEquals(1.25005,evaluate("strength","multiplier",mapOf("%level%" to 100.02))!!,1e-12)
  assertEquals(130.0,evaluate("crit_chance","chance",mapOf("%level%" to 100.0)))
  assertEquals(1.6,evaluate("crit_chance","multiplier",mapOf("%ecoskills_crit_damage%" to 20.0))!!,1e-12)
  assertNull(evaluate("strength","multiplier",emptyMap()))
 }
 @Test fun fortuneFormulaIsNotPresentedAsAChanceAndCurrentNativeRoundingRemainsVisible(){
  assertEquals(1,evaluate("mining_fortune","multiplier",mapOf("%level%" to 25.0))!!.roundToInt())
  assertEquals(2,evaluate("mining_fortune","multiplier",mapOf("%level%" to 50.0))!!.roundToInt())
  val cfg=yaml("combat-menu.yml")
  assertTrue(cfg.getStringList("catalog.stats.mining_fortune.result").any{"%rounded%" in it})
  assertTrue(cfg.getStringList("catalog.stats.wisdom.description").any{"mascotas" in it})
  assertTrue(cfg.getStringList("catalog.stats.treasure_chance.description").any{"No es una probabilidad" in it})
 }
}
