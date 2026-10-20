package com.willfp.ecoskills.skills
import com.willfp.eco.core.Eco
import com.willfp.eco.core.data.keys.PersistentDataKey
import com.willfp.eco.core.data.observation.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import java.util.UUID
class SkillRequirementCacheTest {
 @Test fun `current cache crosses 13 14 50 and lowering without retaining an old answer`(){
  val eco=mock(Eco::class.java);val access=mock(ProfileObservationAccess::class.java)
  val skill=mock(Skill::class.java)
  @Suppress("UNCHECKED_CAST") val key=mock(PersistentDataKey::class.java) as PersistentDataKey<Int>
  `when`(skill.getObservationKey()).thenReturn(key);`when`(eco.profileObservationAccess).thenReturn(access)
  val id=UUID.randomUUID()
  mockStatic(Eco::class.java).use { static ->
   static.`when`<Eco>{Eco.get()}.thenReturn(eco)
   for(level in listOf(13,14,50,13,0)){
    `when`(access.peek(id,setOf(key))).thenReturn(CachedProfileValues(true,mapOf(key to level)))
    assertEquals(level,SkillRequirementViews.peek(id,skill))
   }
  }
  verify(access,never()).storageReader()
 }
 @Test fun `cold key absent session and malformed values stay unknown`(){
  val eco=mock(Eco::class.java);val access=mock(ProfileObservationAccess::class.java);val skill=mock(Skill::class.java)
  @Suppress("UNCHECKED_CAST") val key=mock(PersistentDataKey::class.java) as PersistentDataKey<Int>
  `when`(skill.getObservationKey()).thenReturn(key);`when`(eco.profileObservationAccess).thenReturn(access)
  val id=UUID.randomUUID()
  mockStatic(Eco::class.java).use { static ->
   static.`when`<Eco>{Eco.get()}.thenReturn(eco)
   for(snapshot in listOf(CachedProfileValues.absent(),CachedProfileValues(true,emptyMap()),CachedProfileValues(true,mapOf(key to -1)),CachedProfileValues(true,mapOf(key to "50")))){
    `when`(access.peek(id,setOf(key))).thenReturn(snapshot);assertNull(SkillRequirementViews.peek(id,skill))
   }
  }
  verify(access,never()).storageReader()
 }
}
