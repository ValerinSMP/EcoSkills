package com.willfp.ecoskills.enchanting.persistence;

import com.willfp.ecoskills.api.enchanting.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.willfp.ecoskills.api.enchanting.EnchantingXPProvider.*;
import static com.willfp.ecoskills.api.enchanting.EnchantingXPConsumer.Consumption.*;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;
import static org.mockito.Mockito.*;

class ReceiptProviderTest {
    @TempDir Path directory;
    DurableEnchantingStoreTest fixture;
    DurableEnchantingStore store;
    org.h2.jdbcx.JdbcDataSource pool;
    EnchantingPurchaseEvidence evidence;
    Award award;
    @BeforeEach void setup()throws Exception {
        fixture=new DurableEnchantingStoreTest();fixture.directory=directory;
        pool=fixture.pool("public_receipts");store=fixture.store(pool);
        evidence=new EnchantingPurchaseEvidence(UUID.randomUUID(),fixture.player,"survival",fixture.session,"ecoenchants:sharpness",3,4,
            EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,0,"tariff-v1","a".repeat(64),UUID.randomUUID(),fixture.time.millis());
        var fence=store.acquire("survival",fixture.player,fixture.session,60000);
        award=new Award(evidence.operationId(),evidence.awardId(),evidence.payloadHash(),fence,51.43928459844674,
            new Attribution(UUID.randomUUID(),fixture.session,"original-pet-selection"),PurchaseEvidenceCodec.encode(evidence));
    }
    ReceiptKey key(){return new ReceiptKey("survival",evidence.operationId(),evidence.awardId());}
    EnchantingXPConsumer.Delivery claim()throws Exception {
        var d=store.claim("survival",Kind.FINAL_XP,1,1000).getFirst();
        return new EnchantingXPConsumer.Delivery(evidence,d.awardId(),d.finalXP(),
            new EnchantingXPConsumer.Attribution(d.attribution().captureId(),new EnchantingXPConsumer.AttributionRequest(d.operationId(),d.player(),d.scope(),d.originalSession()),d.attribution().opaqueToken()),d.claim(),d.receiptPayloadHash());
    }
    @Test void lookupReconstructsCommittedOriginalAndMissingDoesNotInventPending()throws Exception {
        assertEquals(State.NOT_FOUND,store.lookupReceipt(key()).state());
        store.award(award,50,l->1000);var observed=store.lookupReceipt(key());
        assertEquals(State.APPLIED,observed.state());assertEquals(evidence,observed.receipt().evidence());
        assertEquals(51.43928459844674,observed.receipt().finalXP());assertEquals(award.attribution().captureId(),observed.receipt().originalAttribution().captureId());
        assertEquals(State.PAYLOAD_CONFLICT,store.lookupReceipt(new ReceiptKey("survival",evidence.operationId(),UUID.randomUUID())).state());
        assertEquals(State.NOT_FOUND,store.lookupReceipt(new ReceiptKey("other",evidence.operationId(),evidence.awardId())).state());
    }
    @Test void verifiesFullReceiptAndRejectsForgedAmountHashTokenEvidenceAndAttempt()throws Exception {
        store.award(award,50,l->1000);var original=claim();assertEquals(State.APPLIED,store.verifyDelivery(original).state());
        var amount=new EnchantingXPConsumer.Delivery(evidence,original.awardId(),original.finalXP()+1,original.originalAttribution(),original.deliveryAttempt(),original.receiptPayloadHash());
        assertEquals(State.PAYLOAD_CONFLICT,store.verifyDelivery(amount).state());
        var hash=new EnchantingXPConsumer.Delivery(evidence,original.awardId(),original.finalXP(),original.originalAttribution(),original.deliveryAttempt(),"b".repeat(64));
        assertEquals(State.PAYLOAD_CONFLICT,store.verifyDelivery(hash).state());
        var token=new EnchantingXPConsumer.Attribution(original.originalAttribution().captureId(),original.originalAttribution().request(),"new-pet");
        assertEquals(State.PAYLOAD_CONFLICT,store.verifyDelivery(new EnchantingXPConsumer.Delivery(evidence,original.awardId(),original.finalXP(),token,original.deliveryAttempt(),original.receiptPayloadHash())).state());
        var changed=new EnchantingPurchaseEvidence(evidence.operationId(),evidence.playerId(),evidence.scope(),evidence.sessionId(),evidence.enchantmentKey(),3,5,evidence.mode(),0,evidence.tariffVersion(),evidence.contextHash(),evidence.deliveryReceiptId(),evidence.confirmedAtEpochMillis());
        assertEquals(State.PAYLOAD_CONFLICT,store.verifyDelivery(new EnchantingXPConsumer.Delivery(changed,original.awardId(),original.finalXP(),original.originalAttribution(),original.deliveryAttempt(),original.receiptPayloadHash())).state());
        assertEquals(State.REJECTED,store.verifyDelivery(new EnchantingXPConsumer.Delivery(evidence,original.awardId(),original.finalXP(),original.originalAttribution(),UUID.randomUUID(),original.receiptPayloadHash())).state());
    }
    @Test void ackRequiresCommittedOutcomeExactPayloadAndCurrentUnexpiredAttempt()throws Exception {
        store.award(award,50,l->1000);var original=claim();
        for(var rejected:List.of(RETRY,REVIEW,PAYLOAD_CONFLICT))assertEquals(Ack.REJECTED,store.acknowledgeDelivery(original,rejected));
        fixture.time.now.addAndGet(1001);
        assertEquals(State.REJECTED,store.verifyDelivery(original).state());assertEquals(Ack.STALE_ATTEMPT,store.acknowledgeDelivery(original,APPLIED));
        var replay=claim();assertNotEquals(original.deliveryAttempt(),replay.deliveryAttempt());assertEquals(original.awardId(),replay.awardId());
        assertEquals(original.originalAttribution(),replay.originalAttribution());
        assertEquals(Ack.STALE_ATTEMPT,store.acknowledgeDelivery(original,APPLIED));
        assertEquals(Ack.ACKED,store.acknowledgeDelivery(replay,ALREADY_APPLIED));
        assertEquals(Ack.ALREADY_ACKED,store.acknowledgeDelivery(replay,ALREADY_APPLIED));
        assertEquals(Ack.STALE_ATTEMPT,store.acknowledgeDelivery(original,APPLIED));
        assertEquals(State.REJECTED,store.verifyDelivery(replay).state());
    }
    @Test void zeroFinalXPStillHasCompleteCommittedReceiptAndNoDelivery()throws Exception {
        var zero=new Award(award.operation(),award.awardId(),award.payloadHash(),award.fence(),0,award.attribution(),award.confirmedEvidence());
        store.award(zero,50,l->1000);assertEquals(0,store.lookupReceipt(key()).receipt().finalXP());
        assertTrue(store.claim("survival",Kind.FINAL_XP,32,1000).isEmpty());
    }
    @Test void corruptionFailsClosedAndDoesNotWrite()throws Exception {
        store.award(award,50,l->1000);
        try(var c=pool.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE test_enchanting_receipts SET evidence_payload='corrupt'");}
        assertEquals(State.REJECTED,store.lookupReceipt(key()).state());
        assertEquals(award.modifiedFinalXP(),fixture.nativeXP(pool));
    }
    @Test void codecIsVersionedBoundedAndRejectsTrailingOrInvalidData()throws Exception {
        assertEquals(evidence,PurchaseEvidenceCodec.decode(PurchaseEvidenceCodec.encode(evidence)));
        assertThrows(java.io.IOException.class,()->PurchaseEvidenceCodec.decode("a".repeat(8193)));
        assertThrows(java.io.IOException.class,()->PurchaseEvidenceCodec.decode("not base64!"));
        var bytes=Base64.getDecoder().decode(PurchaseEvidenceCodec.encode(evidence));
        assertThrows(java.io.IOException.class,()->PurchaseEvidenceCodec.decode(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes,bytes.length+1))));
    }
    @Test void authorizedCapabilityScopedRevokedAndClosedWithoutMainThreadIO()throws Exception {
        Object owner=new Object();store.award(award,50,l->1000);var d=claim();
        var readiness=new Readiness(false,1,1,Set.of("native_writer_not_integrated"));
        var service=new DurableReceiptService(store,owner,"survival",()->readiness);
        try {
            assertFalse(service.readiness().ready());assertThrows(SecurityException.class,()->service.authorizeConsumer(new Object()));
            var access=service.authorizeConsumer(owner);
            assertEquals(State.APPLIED,access.lookupReceipt(key()).toCompletableFuture().get(3,TimeUnit.SECONDS).state());
            assertEquals(State.APPLIED,access.verifyDelivery(d).toCompletableFuture().get(3,TimeUnit.SECONDS).state());
            assertEquals(State.UNAVAILABLE,access.lookupReceipt(new ReceiptKey("other",evidence.operationId(),evidence.awardId())).toCompletableFuture().get().state());
            service.revokeConsumer(owner);assertEquals(State.UNAVAILABLE,access.verifyDelivery(d).toCompletableFuture().get().state());
            assertEquals(Ack.UNAVAILABLE,access.acknowledgeDelivery(d,APPLIED).toCompletableFuture().get());
        } finally {service.close();}
        assertFalse(service.readiness().ready());assertThrows(SecurityException.class,()->service.authorizeConsumer(owner));
    }
    @Test void blockedPoolTimeoutDoesNotReleaseExecutingWorkerOrGrowQueue()throws Exception {
        var blocked=mock(DataSource.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(blocked.getConnection()).thenAnswer(invocation->{assertNotEquals(Thread.currentThread().getName(),"Test worker");entered.countDown();release.await(3,TimeUnit.SECONDS);throw new java.sql.SQLException("pool unavailable");});
        var blockedStore=new DurableEnchantingStore(blocked,"test_",fixture.time,fixture.gateway);Object owner=new Object();
        try(var service=new DurableReceiptService(blockedStore,owner,"survival",()->new Readiness(false,1,1,Set.of("not_bound")),Duration.ofMillis(100),1)) {
            var access=service.authorizeConsumer(owner);var first=access.lookupReceipt(key());assertTrue(entered.await(2,TimeUnit.SECONDS));
            var queued=access.lookupReceipt(key());var rejected=access.lookupReceipt(key());
            assertEquals(State.UNAVAILABLE,rejected.toCompletableFuture().get(1,TimeUnit.SECONDS).state());
            assertEquals(State.UNAVAILABLE,first.toCompletableFuture().get(1,TimeUnit.SECONDS).state());
            assertEquals(State.UNAVAILABLE,queued.toCompletableFuture().get(1,TimeUnit.SECONDS).state());
            verify(blocked,times(1)).getConnection();
        }finally{release.countDown();}
    }
    @Test void publicRegistrationRequiresExactOwnerAndConsumerAndRevokesOldAccess()throws Exception {
        var owner=mock(org.bukkit.plugin.Plugin.class);var consumer=mock(EnchantingXPConsumer.class);
        try(var service=new DurableReceiptService(store,owner,"survival",()->new Readiness(false,1,1,Set.of("not_bound")))) {
            EnchantingXPProvider provider=service;
            assertThrows(SecurityException.class,()->provider.registerConsumer(mock(org.bukkit.plugin.Plugin.class),consumer));
            var access=provider.registerConsumer(owner,consumer);
            assertEquals(State.NOT_FOUND,access.lookupReceipt(key()).toCompletableFuture().get(3,TimeUnit.SECONDS).state());
            assertThrows(IllegalStateException.class,()->provider.registerConsumer(owner,mock(EnchantingXPConsumer.class)));
            assertThrows(SecurityException.class,()->provider.unregisterConsumer(owner,mock(EnchantingXPConsumer.class)));
            provider.unregisterConsumer(owner,consumer);
            assertEquals(State.UNAVAILABLE,access.lookupReceipt(key()).toCompletableFuture().get(3,TimeUnit.SECONDS).state());
        }
    }
    @Test void forgedAckPayloadCannotFinishTheRealAttempt()throws Exception {
        store.award(award,50,l->1000);var original=claim();
        var forged=new EnchantingXPConsumer.Delivery(evidence,original.awardId(),original.finalXP()+1,original.originalAttribution(),original.deliveryAttempt(),original.receiptPayloadHash());
        assertEquals(Ack.PAYLOAD_CONFLICT,store.acknowledgeDelivery(forged,APPLIED));
        assertEquals(State.APPLIED,store.verifyDelivery(original).state());
        assertEquals(Ack.ACKED,store.acknowledgeDelivery(original,APPLIED));
    }
    @Test void explicitCallbackReconciliationReviewsOnlyExpiredRunningAndNeverReplaysReady()throws Exception {
        var levels=new Award(award.operation(),award.awardId(),award.payloadHash(),award.fence(),250,award.attribution(),award.confirmedEvidence());
        store.award(levels,50,l->100);assertEquals(0,store.reconcileUncertainLevelCallbacks("survival",32));
        assertEquals(1,store.claim("survival",Kind.LEVEL_EFFECT,1,1000).size());
        assertEquals(0,store.reconcileUncertainLevelCallbacks("survival",32));fixture.time.now.addAndGet(1001);
        assertEquals(0,store.reconcileUncertainLevelCallbacks("other",32));assertEquals(1,store.reconcileUncertainLevelCallbacks("survival",1));
        assertEquals(0,store.reconcileUncertainLevelCallbacks("survival",32));
        assertEquals(1,store.claim("survival",Kind.LEVEL_EFFECT,32,1000).size());
    }
}
