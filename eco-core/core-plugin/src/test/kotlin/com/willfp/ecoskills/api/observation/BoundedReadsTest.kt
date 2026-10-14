package com.willfp.ecoskills.api.observation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
class BoundedReadsTest {
 @Test fun saturationRejectsAndTimeoutRetainsActualWorker() {
  BoundedReads(1,1,150).use { reads ->
   val entered=CountDownLatch(1);val release=CountDownLatch(1)
   val first=reads.submit("first",{ _:com.willfp.eco.core.data.observation.ReadControl ->entered.countDown();release.await();"ok"},{it})
   assertTrue(entered.await(1,TimeUnit.SECONDS))
   val queued=reads.submit("queued",{ _:com.willfp.eco.core.data.observation.ReadControl ->"ok"},{it})
   val rejected=reads.submit("other",{ _:com.willfp.eco.core.data.observation.ReadControl ->"ok"},{it})
   assertEquals("busy",rejected.get(1,TimeUnit.SECONDS));assertEquals("timeout",first.get(1,TimeUnit.SECONDS))
   assertEquals("timeout",queued.get(1,TimeUnit.SECONDS));assertEquals(1,reads.activeReads())
   release.countDown()
  }
 }
 @Test fun identicalRequestsCoalesceAndOneSubscriberCannotCancelOther() {
  BoundedReads(1,2,1000).use { reads ->
   val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
   val work=BoundedReads.Work<String>{entered.countDown();calls.incrementAndGet();release.await();"ok"}
   val first=reads.submit("same",work,{it});assertTrue(entered.await(1,TimeUnit.SECONDS))
   val second=reads.submit("same",work,{it});first.cancel(false);release.countDown()
   assertEquals("ok",second.get(1,TimeUnit.SECONDS));assertEquals(1,calls.get())
  }
 }
 @Test fun completionIsOffCallerAndShutdownRejects() {
  val reads=BoundedReads(1,2,1000)
  val thread=reads.submit("thread",{ _:com.willfp.eco.core.data.observation.ReadControl ->Thread.currentThread().name},{it}).get(1,TimeUnit.SECONDS)
  assertTrue(thread.startsWith("EcoSkills-observation-read-"));reads.close()
  assertEquals("service_stopping",reads.submit("later",{ _:com.willfp.eco.core.data.observation.ReadControl ->"bad"},{it}).get())
 }
 @Test fun invalidBatchNeverTouchesStorage() {
  val invalid=java.util.HashSet<java.util.UUID>();(invalid as java.util.Set<java.util.UUID?>).add(null)
  assertThrows(IllegalArgumentException::class.java){ObservationService.storedBatch(invalid,ObservationRequest.all())}
  assertThrows(IllegalArgumentException::class.java){ObservationService.storedBatch(emptySet(),ObservationRequest.all())}
  assertThrows(IllegalArgumentException::class.java){ObservationService.storedBatch((1..17).map{java.util.UUID.randomUUID()}.toSet(),ObservationRequest.all())}
 }
}
