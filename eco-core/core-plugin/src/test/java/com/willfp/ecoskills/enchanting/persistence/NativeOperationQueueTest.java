package com.willfp.ecoskills.enchanting.persistence;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeOperationQueueTest {
 @Test void completeOperationsRemainOrderedAndOldResumeCannotThawANewerFreeze(){
  UUID player=UUID.randomUUID();var review=new AtomicInteger();var values=new ArrayList<Integer>();var q=new NativeOperationQueue(id->{},(id,why)->review.incrementAndGet());q.install();
  try{long old=q.freeze(player);assertTrue(NativeOperationQueue.defer(player,()->values.add(1)));long current=q.freeze(player);assertTrue(NativeOperationQueue.defer(player,()->values.add(2)));q.resume(player,old);assertTrue(values.isEmpty());q.resume(player,current);assertEquals(List.of(1,2),values);assertEquals(0,review.get());}finally{q.close();}
 }
 @Test void uncertainOperationIsRemovedBeforeCallbackAndRemainingWorkIsQuarantined(){
  UUID p=UUID.randomUUID();var calls=new AtomicInteger();var review=new AtomicInteger();var q=new NativeOperationQueue(id->{},(id,why)->review.incrementAndGet());q.install();
  try{long epoch=q.freeze(p);NativeOperationQueue.defer(p,()->{calls.incrementAndGet();throw new IllegalStateException("Unknown callback result");});NativeOperationQueue.defer(p,()->calls.addAndGet(100));q.resume(p,epoch);assertEquals(1,calls.get());assertEquals(1,review.get());assertFalse(q.ready(p));}finally{q.close();}
 }
 @Test void guardedNativeEntryDefersBeforeReadingProfileOrFiringXPEvents(){
  UUID id=UUID.randomUUID();var player=org.mockito.Mockito.mock(org.bukkit.OfflinePlayer.class);org.mockito.Mockito.when(player.getUniqueId()).thenReturn(id);
  var skill=org.mockito.Mockito.mock(com.willfp.ecoskills.skills.Skill.class);var map=new com.willfp.ecoskills.skills.SkillLevelMap(player);
  var q=new NativeOperationQueue(p->{},(p,why)->{});q.install();
  try{q.freeze(id);map.giveXP(skill,1.0);org.mockito.Mockito.verifyNoInteractions(skill);}finally{q.close();}
 }
}
