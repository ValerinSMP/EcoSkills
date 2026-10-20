package com.willfp.ecoskills.skills;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ViewUpdateQueueTest {
 @Test void combinesSameTickSkillsAndReadsCurrentPublicationAtFlush(){
  var tasks=new ArrayList<Runnable>();var observed=new ArrayList<String>();int[] level={13};
  var queue=new ViewUpdateQueue(4,tasks::add,(id,keys)->observed.add(level[0]+":"+keys.size()));
  var id=UUID.randomUUID();queue.request(id,Set.of("enchanting"));level[0]=14;
  queue.request(id,Set.of("enchanting","mining"));level[0]=50;
  assertEquals(1,tasks.size());tasks.getFirst().run();assertEquals(List.of("50:2"),observed);assertEquals(0,queue.size());
 }
 @Test void retiredSessionCannotConsumeNewSessionBatch(){
  var tasks=new ArrayList<Runnable>();var observed=new ArrayList<Set<String>>();
  var queue=new ViewUpdateQueue(4,tasks::add,(id,keys)->observed.add(keys));var id=UUID.randomUUID();
  queue.request(id,Set.of("old"));queue.retire(id);queue.request(id,Set.of("new"));
  tasks.get(0).run();assertTrue(observed.isEmpty());tasks.get(1).run();assertEquals(List.of(Set.of("new")),observed);
 }
 @Test void observersAreIsolatedAndCapacityDoesNotEvictAnotherView(){
  var tasks=new ArrayList<Runnable>();var seen=new ArrayList<UUID>();
  var queue=new ViewUpdateQueue(1,tasks::add,(id,keys)->seen.add(id));var a=UUID.randomUUID();var b=UUID.randomUUID();
  queue.request(a,Set.of("enchanting"));queue.request(b,Set.of("enchanting"));assertEquals(1,queue.size());
  tasks.getFirst().run();assertEquals(List.of(a),seen);queue.request(b,Set.of("enchanting"));tasks.getLast().run();assertEquals(List.of(a,b),seen);
 }
 @Test void disableDropsPendingAndSchedulingFailureReleasesCapacity(){
  var tasks=new ArrayList<Runnable>();var seen=new ArrayList<UUID>();var id=UUID.randomUUID();
  var queue=new ViewUpdateQueue(1,tasks::add,(uuid,keys)->seen.add(uuid));queue.request(id,Set.of("s"));queue.clear();tasks.getFirst().run();assertTrue(seen.isEmpty());
  var failed=new ViewUpdateQueue(1,task->{throw new IllegalStateException();},(uuid,keys)->{});
  assertThrows(IllegalStateException.class,()->failed.request(id,Set.of("s")));assertEquals(0,failed.size());
 }
}
