package com.willfp.ecoskills.api.observation
import com.willfp.eco.core.data.keys.PersistentDataKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class ObservationCacheTest {
 private val uuid=UUID.randomUUID()
 private val level=Mockito.mock(PersistentDataKey::class.java)
 private val xp=Mockito.mock(PersistentDataKey::class.java)
 private val stat=Mockito.mock(PersistentDataKey::class.java)
 private val effect=Mockito.mock(PersistentDataKey::class.java)
 private val pref=Mockito.mock(PersistentDataKey::class.java)
 private fun catalog()=Catalog(1,listOf(Definition("mining","Mining",level,xp,50,listOf(10.0,20.0))),
  listOf(Definition("strength","Strength",stat)),listOf(Definition("passive","Passive",effect)),
  mapOf("actionBarEnabled" to pref),emptyList(),false,emptySet(),false,null)
 private fun observe(values:Map<PersistentDataKey<*>,Any>,source:String="CACHE",mods:List<Map<String,Any>>?=null)=
  ObservationService.assemble(uuid,catalog(),values,true,source,ObservationRequest.persistentOnly(),mods).asMap()
 private fun row(data:Map<String,Any>,section:String):Map<*,*> = ((data[section] as Map<*,*>)["items"] as List<*>).first() as Map<*,*>
 private fun number(row:Map<*,*>,key:String)=(row[key] as Map<*,*>)["value"]
 @Test fun coldKeysAreAbsentNotDefaults() {
  val before=emptyMap<PersistentDataKey<*>,Any>()
  val data=observe(before)
  assertNull(number(row(data,"skills"),"level"));assertNull(number(row(data,"stats"),"base"))
  assertEquals("key_not_cached",(row(data,"stats")["base"] as Map<*,*>)["reason"])
  assertEquals(emptyMap<PersistentDataKey<*>,Any>(),before)
 }
 @Test fun storedZeroAndFalseArePresent() {
  val data=observe(mapOf(level to 0,xp to 0.0,stat to 0,effect to 0,pref to false),"STORAGE")
  assertEquals(0,number(row(data,"skills"),"level"));assertEquals(0,number(row(data,"stats"),"base"))
  assertEquals(false,((data["preferences"] as Map<*,*>)["actionBarEnabled"] as Map<*,*>)["value"])
  assertNull(number(row(data,"stats"),"modified"))
 }
 @Test fun exactDecimalsAndNativeAddThenMultiply() {
  val mods=listOf(mapOf("statId" to "strength","operation" to "MULTIPLY","amount" to 2.0),mapOf("statId" to "strength","operation" to "ADD","amount" to 0.02))
  val data=observe(mapOf(stat to 100),mods=mods)
  assertEquals(200.04,number(row(data,"stats"),"modified"));assertEquals(200,number(row(data,"stats"),"legacy"))
  val removed=observe(mapOf(stat to 100),mods=emptyList())
  assertEquals(100.0,number(row(removed,"stats"),"modified"))
 }
 @Test fun missingXpKeepsKnownLevelAndRequirement() {
  val data=observe(mapOf(level to 1))
  assertEquals(1,number(row(data,"skills"),"level"));assertEquals(20.0,number(row(data,"skills"),"requiredXp"))
  assertNull(number(row(data,"skills"),"xpWithinLevel"));assertNull(number(row(data,"skills"),"progress"))
 }
 @Test fun maximumAndNonfiniteHaveExplicitAbsence() {
  val data=observe(mapOf(level to 50,xp to Double.NaN))
  assertNull(number(row(data,"skills"),"requiredXp"));assertEquals("at_max_level",(row(data,"skills")["requiredXp"] as Map<*,*>)["reason"])
  assertNull(number(row(data,"skills"),"xpWithinLevel"))
 }
 @Test fun deepCopiesRemainImmutableAndDoNotAlias() {
  val nested=linkedMapOf<String,Any?>("value" to 1)
  val list=mutableListOf<Any>(nested)
  val data=PlayerObservation(ObservationValues.map("items",list)).asMap()
  nested["value"]=99;list.clear()
  assertEquals(1,((data["items"] as List<*>)[0] as Map<*,*>)["value"])
  assertThrows(UnsupportedOperationException::class.java){(data["items"] as MutableList<*>).clear()}
  assertThrows(UnsupportedOperationException::class.java){(data as MutableMap<*,*>).clear()}
 }
 @Test fun cacheGetterRejectsAsyncBeforeAnyLookup() {
  Mockito.mockStatic(org.bukkit.Bukkit::class.java).use { mock ->
   mock.`when`<Boolean>{org.bukkit.Bukkit.isPrimaryThread()}.thenReturn(false)
   assertThrows(IllegalStateException::class.java){ObservationService.cached(uuid)}
   mock.verify{org.bukkit.Bukkit.isPrimaryThread()}
  }
 }
 @Test fun unsupportedFamiliesAreHonestAndOfflineSessionIsAbsent() {
  val result=ObservationService.assemble(uuid,catalog(),emptyMap(),false,"STORAGE",ObservationRequest.all()).asMap()
  assertEquals("disabled",(result["rankings"] as Map<*,*>)["reason"])
  assertEquals("session_required",(result["modifiers"] as Map<*,*>)["reason"])
  assertEquals("not_materialized",(result["presentation"] as Map<*,*>)["reason"])
 }
 @Test fun storedPersistentOnlyIsCompleteWithoutInventedSession() {
  val result=observe(mapOf(level to 1,xp to .02,stat to 100,effect to 0,pref to false),"STORAGE")
  assertEquals("available",result["status"]);assertNull(number(row(result,"stats"),"modified"))
 }
 @Test fun sessionIdentityDoesNotCarryManaIntoReconnectOrTransfer() {
  val first=Mockito.mock(org.bukkit.entity.Player::class.java);val second=Mockito.mock(org.bukkit.entity.Player::class.java)
  Mockito.`when`(first.uniqueId).thenReturn(uuid);Mockito.`when`(second.uniqueId).thenReturn(uuid)
  Mockito.mockStatic(org.bukkit.Bukkit::class.java).use { mock ->
   mock.`when`<Boolean>{org.bukkit.Bukkit.isPrimaryThread()}.thenReturn(true)
   mock.`when`<org.bukkit.entity.Player>{org.bukkit.Bukkit.getPlayer(uuid)}.thenReturn(first)
   ObservationSessions.markMagicWrite(first,"mana");assertTrue(ObservationSessions.hasCurrentMagic(first,"mana"))
   mock.`when`<org.bukkit.entity.Player>{org.bukkit.Bukkit.getPlayer(uuid)}.thenReturn(second)
   assertFalse(ObservationSessions.hasCurrentMagic(second,"mana"))
   ObservationSessions.markMagicWrite(second,"mana");assertTrue(ObservationSessions.hasCurrentMagic(second,"mana"))
   ObservationSessions.clear();assertFalse(ObservationSessions.hasCurrentMagic(second,"mana"))
  }
 }
 @Test fun unsupportedFormulaNeverFallsBackToRequirementsWhenNativeUsesFormula() {
  val c=catalog().copy(skills=listOf(Definition("mining","Mining",level,xp,50,listOf(10.0,20.0),null,true)))
  val result=ObservationService.assemble(uuid,c,mapOf(level to 1,xp to 0.0),true,"CACHE",ObservationRequest.persistentOnly()).asMap()
  assertNull(number(row(result,"skills"),"requiredXp"))
 }
 @Test fun payloadAndMutableNumbersAreRejected() {
  assertThrows(IllegalArgumentException::class.java){PlayerObservation(ObservationValues.map("value",java.util.concurrent.atomic.AtomicInteger(1)))}
  assertThrows(IllegalArgumentException::class.java){PlayerObservation(ObservationValues.map("items",(1..512).map{"x".repeat(256)}))}
 }
 @Test fun snapshotDoesNotMutateInputCollections() {
  val values=mutableMapOf<PersistentDataKey<*>,Any>(stat to 100)
  val mods=mutableListOf<Map<String,Any>>(mapOf("statId" to "strength","operation" to "ADD","amount" to .02))
  val before=values.toMap();val beforeMods=mods.toList();observe(values,mods=mods)
  assertEquals(before,values);assertEquals(beforeMods,mods)
 }
}
