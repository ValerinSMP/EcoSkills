package com.willfp.ecoskills.enchanting.persistence;
import com.willfp.eco.core.data.handlers.*;
import com.willfp.eco.core.data.keys.*;
import com.willfp.ecoskills.api.enchanting.*;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;
class RuntimeProviderTest {
    final UUID player=UUID.randomUUID();final Plugin sourceOwner=mock(Plugin.class),consumerOwner=mock(Plugin.class);
    final ExecutorService main=Executors.newSingleThreadExecutor(r->new Thread(r,"simulated-Paper-main"));
    final AtomicReference<Thread> mainThread=new AtomicReference<>();
    final DurableEnchantingStoreTest.Time clock=new DurableEnchantingStoreTest.Time();
    final AtomicReference<CompletableFuture<EnchantingPurchaseAuthority.Verification>> proofGate=new AtomicReference<>();
    final AtomicReference<CountDownLatch> clockReadSignal=new AtomicReference<>();
    final AtomicLong nanos=new AtomicLong();
    final AtomicBoolean online=new AtomicBoolean(true);
    final AtomicReference<CompletableFuture<Void>> flushGate=new AtomicReference<>();
    final AtomicInteger flushes=new AtomicInteger();
    final AtomicInteger modifiers=new AtomicInteger(),captures=new AtomicInteger(),grants=new AtomicInteger();
    final AtomicBoolean failModifier=new AtomicBoolean(),queueExtraXP=new AtomicBoolean(),failPublication=new AtomicBoolean();
    final ConcurrentMap<String,Number> cache=new ConcurrentHashMap<>();
    DurableRuntimeProvider provider;DurableEnchantingStore store;JdbcNativeEnchantingGateway gateway;DataSource pool;
    EnchantingXPProvider.Access access;PersistentDataHandler writer;
    PersistentDataKey<Double> xp;PersistentDataKey<Integer> level,reward;
    final Map<UUID,EnchantingXPConsumer.Delivery> delivered=new ConcurrentHashMap<>();
    final Map<UUID,EnchantingPurchaseAuthority.Verification> confirmations=new ConcurrentHashMap<>();
    <T>PersistentDataKey<T> key(String name,PersistentDataKeyType<T> type){
        @SuppressWarnings("unchecked") PersistentDataKey<T> k=mock(PersistentDataKey.class);when(k.getKey()).thenReturn(new NamespacedKey("ecoskills",name));when(k.getType()).thenReturn(type);return k;
    }
    <T>T main(Callable<T> work)throws Exception{return main.submit(()->{mainThread.set(Thread.currentThread());return work.call();}).get(8,TimeUnit.SECONDS);}
    <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(8,TimeUnit.SECONDS);}
    @BeforeEach void setup()throws Exception {
        pool=new JdbcNativeEnchantingGatewayTest().pool();gateway=new JdbcNativeEnchantingGatewayTest().gateway();
        xp=key("enchanting_xp",PersistentDataKeyType.DOUBLE);level=key("enchanting",PersistentDataKeyType.INT);reward=key("wisdom",PersistentDataKeyType.INT);
        writer=new PersistentDataHandler("simulated-native-handler",1){public Set<UUID>getSavedUUIDs(){return Set.of();}};
        PersistentDataKeyType.DOUBLE.registerSerializer(writer,new DataTypeSerializer<Double>(){public Double readAsync(UUID p,PersistentDataKey<Double> k){return null;}public void writeAsync(UUID p,PersistentDataKey<Double> k,Double v){throw new AssertionError("Guard bypass");}});
        PersistentDataKeyType.INT.registerSerializer(writer,new DataTypeSerializer<Integer>(){public Integer readAsync(UUID p,PersistentDataKey<Integer> k){return null;}public void writeAsync(UUID p,PersistentDataKey<Integer> k,Integer v){throw new AssertionError("Guard bypass");}});
        ProfileMutationAccess mutation=new ProfileMutationAccess(){
            public boolean supported(){return true;}
            public void install(Factory factory){assertSame(mainThread.get(),Thread.currentThread());writer.installProfileWriteBarrier(factory.create(pool,"eco_"));}
            public CompletionStage<Void>flush(UUID p){assertSame(mainThread.get(),Thread.currentThread());flushes.incrementAndGet();var gate=flushGate.get();return gate==null?writer.awaitProfileWrites(p):gate.thenCompose(v->writer.awaitProfileWrites(p));}
            public CompletionStage<Void>flushAll(){return writer.awaitProfileWrites(null);}
            public boolean publish(UUID p,Set<PersistentDataKey<?>>owned,Map<PersistentDataKey<?>,Object> values){assertSame(mainThread.get(),Thread.currentThread());cache.clear();values.forEach((k,v)->cache.put(k.getKey().toString(),(Number)v));return true;}
        };
        provider=main(()->new DurableRuntimeProvider(new DurableRuntimeProvider.Host(){
            public boolean isMain(){return Thread.currentThread()==mainThread.get();}
            public void executeMain(Runnable task){if(isMain())task.run();else main.execute(task);}
            public ProfileMutationAccess access(){return mutation;}
            public Plugin purchaseOwner(){return sourceOwner;}public Plugin consumerOwner(){return consumerOwner;}
            public long monotonicNanos(){var signal=clockReadSignal.get();if(signal!=null)signal.countDown();return nanos.get();}
            public boolean online(UUID p){return online.get()&&player.equals(p);}public Collection<UUID>onlinePlayers(){return List.of();}
            public JdbcNativeEnchantingGateway gateway(String prefix){return gateway;}
            public DurableEnchantingStore createStore(DataSource p,String prefix){store=new DurableEnchantingStore(p,prefix,clock,gateway);return store;}
            public Map<PersistentDataKey<?>,Class<? extends Number>>keys(){return Map.of(xp,Double.class,level,Integer.class,reward,Integer.class);}
            public void publish(NativeSnapshot s){if(failPublication.get()&&s.values().getOrDefault("ecoskills:enchanting_xp",0.0).doubleValue()>0)throw new IllegalStateException("Simulated cache publication failure");mutation.publish(s.fence().player(),keys().keySet(),new HashMap<>(s.values().entrySet().stream().collect(java.util.stream.Collectors.toMap(e->keys().keySet().stream().filter(k->k.getKey().toString().equals(e.getKey())).findFirst().orElseThrow(),Map.Entry::getValue))));}
            public DurableRuntimeProvider.Modified modify(UUID p,double base){
                assertTrue(isMain());modifiers.incrementAndGet();if(failModifier.get())throw new IllegalStateException("Simulated uncertain modifier callback");
                if(queueExtraXP.get())assertTrue(NativeOperationQueue.defer(player,()->{double next=cache.getOrDefault("ecoskills:enchanting_xp",0.0).doubleValue()+5;cache.put("ecoskills:enchanting_xp",next);writer.write(player,xp,next);}));
                return new DurableRuntimeProvider.Modified(base,false);
            }
            public void levelCallback(Delivery d){assertTrue(isMain());}
            public void review(UUID p,String reason){}
            public int maxLevel(){return 50;}public double requirement(int level){return 1000;}
        },"survival"));
        await(main(()->provider.initialize()));await(main(()->provider.admit(player)));
        main(()->{
            var consumer=new EnchantingXPConsumer(){
                public CompletionStage<Attribution>capture(AttributionRequest request){assertSame(mainThread.get(),Thread.currentThread());captures.incrementAndGet();return CompletableFuture.completedStage(new Attribution(UUID.randomUUID(),request,"original-pet-snapshot"));}
                public CompletionStage<Consumption>consume(EnchantingXPConsumer.Delivery d){assertSame(mainThread.get(),Thread.currentThread());return access.verifyDelivery(d).thenApply(v->{assertEquals(EnchantingXPProvider.State.APPLIED,v.state());delivered.put(d.awardId(),d);grants.incrementAndGet();return Consumption.APPLIED;});}
            };
            access=provider.registerConsumer(consumerOwner,consumer);
            assertFalse(provider.readiness().ready());
            provider.registerPurchaseAuthority(sourceOwner,confirmation->proofGate.get()!=null?proofGate.get():CompletableFuture.completedStage(confirmations.getOrDefault(confirmation.operationId(),new EnchantingPurchaseAuthority.Verification(EnchantingPurchaseAuthority.State.NOT_COMMITTED,null,null,"not_confirmed"))));
            assertTrue(provider.readiness().ready());return null;
        });
    }
    EnchantingPurchaseAuthority.Confirmation purchase(UUID operation)throws Exception {
        var captured=await(main(()->provider.preparePurchase(sourceOwner,player,operation)));
        var evidence=new EnchantingPurchaseEvidence(operation,player,"survival",captured.request().originalSession(),"minecraft:sharpness",3,4,EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,0,"approved-v1","a".repeat(64),UUID.randomUUID(),clock.millis());
        confirmations.put(operation,new EnchantingPurchaseAuthority.Verification(EnchantingPurchaseAuthority.State.CONFIRMED,evidence,captured,null));
        return new EnchantingPurchaseAuthority.Confirmation("survival",operation,evidence.deliveryReceiptId());
    }
    @AfterEach void close()throws Exception {if(provider!=null)main(()->{provider.close();return null;});if(writer!=null)writer.shutdown();main.shutdownNow();}
    @Test void quotedPurchaseUsesOriginalRewardAcrossReplayAndPolicyChange()throws Exception {
        var confirmation=purchase(UUID.randomUUID());var proof=confirmations.get(confirmation.operationId());
        var quote=new EnchantingPurchasePolicy("simulation/1",EnchantingPurchasePolicy.Curve.power(100,1),Map.of()).quote(EnchantingPurchaseQuote.Context.of(proof.evidence()));
        var original=proof.evidence().withXPQuote(quote);
        confirmations.put(confirmation.operationId(),new EnchantingPurchaseAuthority.Verification(proof.state(),original,proof.originalAttribution(),null));
        var first=await(main(()->provider.grantConfirmedPurchase(sourceOwner,confirmation)));
        assertEquals(100.0,first.receipt().finalXP());assertEquals(original,first.receipt().evidence());
        var changed=new EnchantingPurchasePolicy("simulation/2",EnchantingPurchasePolicy.Curve.power(200,1),Map.of()).quote(EnchantingPurchaseQuote.Context.of(proof.evidence()));
        confirmations.put(confirmation.operationId(),new EnchantingPurchaseAuthority.Verification(proof.state(),proof.evidence().withXPQuote(changed),proof.originalAttribution(),null));
        assertEquals(first,await(main(()->provider.grantConfirmedPurchase(sourceOwner,confirmation))));assertEquals(1,modifiers.get());
    }
    @Test void changedPolicyCannotReplaceAnUncertainDurableCalculation()throws Exception {
        var c=purchase(UUID.randomUUID());var proof=confirmations.get(c.operationId());var context=EnchantingPurchaseQuote.Context.of(proof.evidence());
        var first=proof.evidence().withXPQuote(new EnchantingPurchasePolicy("pending/1",EnchantingPurchasePolicy.Curve.power(100,1),Map.of()).quote(context));
        confirmations.put(c.operationId(),new EnchantingPurchaseAuthority.Verification(proof.state(),first,proof.originalAttribution(),null));
        failModifier.set(true);assertEquals(EnchantingXPProvider.State.UNKNOWN,await(main(()->provider.grantConfirmedPurchase(sourceOwner,c))).state());
        var different=proof.evidence().withXPQuote(new EnchantingPurchasePolicy("pending/2",EnchantingPurchasePolicy.Curve.power(200,1),Map.of()).quote(context));
        failModifier.set(false);confirmations.put(c.operationId(),new EnchantingPurchaseAuthority.Verification(proof.state(),different,proof.originalAttribution(),null));
        assertEquals(EnchantingXPProvider.State.PAYLOAD_CONFLICT,await(main(()->provider.grantConfirmedPurchase(sourceOwner,c))).state());assertEquals(1,modifiers.get());
    }
    @Test void zeroQuotedRewardCommitsReceiptWithoutProgressAndStillReplaysOnce()throws Exception {
        var c=purchase(UUID.randomUUID());var proof=confirmations.get(c.operationId());
        var q=new EnchantingPurchasePolicy("zero-test/1",EnchantingPurchasePolicy.Curve.power(0,1),Map.of()).quote(EnchantingPurchaseQuote.Context.of(proof.evidence()));
        confirmations.put(c.operationId(),new EnchantingPurchaseAuthority.Verification(proof.state(),proof.evidence().withXPQuote(q),proof.originalAttribution(),null));
        var first=await(main(()->provider.grantConfirmedPurchase(sourceOwner,c)));
        assertEquals(EnchantingXPProvider.State.APPLIED,first.state());assertEquals(0,first.receipt().finalXP());assertEquals(0,first.receipt().nativeLevel());
        assertEquals(first,await(main(()->provider.grantConfirmedPurchase(sourceOwner,c))));assertEquals(1,modifiers.get());
    }
    @Test void prePaymentDefaultQuoteIsNotEvidenceAndDoesNotGrantProgress()throws Exception {
        var confirmation=purchase(UUID.randomUUID());var proof=confirmations.get(confirmation.operationId());
        var quote=await(main(()->provider.quotePurchase(sourceOwner,EnchantingPurchaseQuote.Context.of(proof.evidence()))));
        assertEquals(EnchantingPurchaseXP.FORMULA_VERSION,quote.policyVersion());assertEquals(0,modifiers.get());assertEquals(0,grants.get());
    }
    @Test void simulatedAuthorityFlowRepeatsOneNativeAwardAndOneModifierAndRetainsOriginalCapture()throws Exception {
        var c=purchase(UUID.randomUUID());var first=await(main(()->provider.grantConfirmedPurchase(sourceOwner,c)));
        assertEquals(EnchantingXPProvider.State.APPLIED,first.state());assertEquals(51.43928459844674,first.receipt().finalXP());
        var repeated=await(main(()->provider.grantConfirmedPurchase(sourceOwner,c)));assertEquals(first,repeated);assertEquals(1,modifiers.get());
        var again=await(main(()->provider.preparePurchase(sourceOwner,player,c.operationId())));assertEquals(first.receipt().originalAttribution(),again);assertEquals(1,captures.get());
        try(var conn=pool.getConnection()){assertEquals(51.43928459844674,gateway.readForUpdate(conn,player).xp());}
    }
    @Test void wholeQueuedOperationUsesPublishedXPInsteadOfStaleAbsoluteValue()throws Exception {
        queueExtraXP.set(true);var c=purchase(UUID.randomUUID());var applied=await(main(()->provider.grantConfirmedPurchase(sourceOwner,c)));
        assertEquals(EnchantingXPProvider.State.APPLIED,applied.state());await(main(()->writer.awaitProfileWrites(player)));
        try(var conn=pool.getConnection()){assertEquals(applied.receipt().nativeXP()+5,gateway.readForUpdate(conn,player).xp(),1e-12);}
    }
    @Test void missingConfirmationDoesNotGrantAndOriginalIntentSurvivesSessionChange()throws Exception {
        UUID operation=UUID.randomUUID();var original=await(main(()->provider.preparePurchase(sourceOwner,player,operation)));
        var missing=new EnchantingPurchaseAuthority.Confirmation("survival",operation,UUID.randomUUID());
        assertEquals(EnchantingXPProvider.State.PENDING,await(main(()->provider.grantConfirmedPurchase(sourceOwner,missing))).state());
        main(()->{provider.retire(player);return null;});await(main(()->provider.admit(player)));
        assertEquals(original,await(main(()->provider.preparePurchase(sourceOwner,player,operation))));assertEquals(0,modifiers.get());
    }
    @Test void uncertainNativeModifierIsNotFiredAgainAfterCalculationLeaseExpires()throws Exception {
        var c=purchase(UUID.randomUUID());failModifier.set(true);
        assertEquals(EnchantingXPProvider.State.UNKNOWN,await(main(()->provider.grantConfirmedPurchase(sourceOwner,c))).state());
        clock.now.addAndGet(30001);failModifier.set(false);
        assertEquals(EnchantingXPProvider.State.REJECTED,await(main(()->provider.grantConfirmedPurchase(sourceOwner,c))).state());assertEquals(1,modifiers.get());
    }
    @Test void committedReceiptIsReturnedEvenWhenTheSourceLedgerIsTemporarilyUnavailable()throws Exception {
        var c=purchase(UUID.randomUUID());var committed=await(main(()->provider.grantConfirmedPurchase(sourceOwner,c)));confirmations.clear();
        assertEquals(committed,await(main(()->provider.grantConfirmedPurchase(sourceOwner,c))));assertEquals(1,modifiers.get());
        var wrong=new EnchantingPurchaseAuthority.Confirmation("survival",c.operationId(),UUID.randomUUID());
        assertEquals(EnchantingXPProvider.State.PAYLOAD_CONFLICT,await(main(()->provider.grantConfirmedPurchase(sourceOwner,wrong))).state());
    }
    @Test void participantDisableRevokesReadinessWithoutInventingAnotherCaptureOrGrant()throws Exception {
        main(()->{provider.participantDisabled(sourceOwner);assertFalse(provider.readiness().ready());return null;});
        assertEquals(0,captures.get());assertEquals(0,modifiers.get());
    }
    @Test void committedGrantWithFailedPublicationCanReconcileWithoutGrantingAgain()throws Exception {
        var confirmation=purchase(UUID.randomUUID());failPublication.set(true);
        assertEquals(EnchantingXPProvider.State.UNKNOWN,await(main(()->provider.grantConfirmedPurchase(sourceOwner,confirmation))).state());
        var evidence=confirmations.get(confirmation.operationId()).evidence();
        assertEquals(EnchantingXPProvider.State.APPLIED,await(access.lookupReceipt(new EnchantingXPProvider.ReceiptKey("survival",evidence.operationId(),evidence.awardId()))).state());
        failPublication.set(false);var resumed=new AtomicInteger();
        main(()->{assertTrue(NativeOperationQueue.defer(player,resumed::incrementAndGet));return null;});
        await(main(()->provider.admit(player)));assertEquals(1,resumed.get());
        assertEquals(EnchantingXPProvider.State.APPLIED,await(main(()->provider.grantConfirmedPurchase(sourceOwner,confirmation))).state());assertEquals(1,modifiers.get());
    }

    void advance(long millis){clock.now.addAndGet(millis);nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));}
    @Test void twentySecondExpiryIsReadmittedBeforeCaptureWithoutChangingSession()throws Exception {
        var original=main(()->provider.session(player).orElseThrow());int before=flushes.get();
        advance(19999);assertTrue(main(()->provider.session(player).isPresent()));advance(1);
        assertTrue(main(()->provider.readiness().ready()));assertTrue(main(()->provider.session(player).isEmpty()));
        var capture=await(main(()->provider.preparePurchase(sourceOwner,player,UUID.randomUUID())));
        assertEquals(original.originalSession(),capture.request().originalSession());
        assertEquals(before+1,flushes.get());assertTrue(main(()->provider.session(player).isPresent()));
        assertEquals(0,modifiers.get());assertEquals(0,grants.get());
    }
    @Test void expiredSqlLeaseAfterSixtyThreeSecondsIsReacquiredBeforeCapture()throws Exception {
        var original=main(()->provider.session(player).orElseThrow());advance(63000);
        assertTrue(main(()->provider.session(player).isEmpty()));
        var capture=await(main(()->provider.preparePurchase(sourceOwner,player,UUID.randomUUID())));
        assertEquals(original.originalSession(),capture.request().originalSession());
        assertTrue(main(()->provider.session(player).isPresent()));assertEquals(1,captures.get());
    }
    @Test void competingUnexpiredSqlLeaseBlocksCaptureAndNeverGrants()throws Exception {
        advance(63000);UUID other=UUID.randomUUID();assertNotNull(store.acquire("survival",player,other,60000));
        assertThrows(ExecutionException.class,()->await(main(()->provider.preparePurchase(sourceOwner,player,UUID.randomUUID()))));
        assertTrue(main(()->provider.session(player).isEmpty()));assertEquals(0,captures.get());assertEquals(0,modifiers.get());assertEquals(0,grants.get());
    }
    @Test void lateJoinAfterInitializationThenIdlePurchaseUsesThatSession()throws Exception {
        main(()->{online.set(false);provider.retire(player);return null;});
        advance(63000);
        await(main(()->{online.set(true);provider.joined(player);return provider.admit(player);}));
        var joined=main(()->provider.session(player).orElseThrow());advance(63000);
        var capture=await(main(()->provider.preparePurchase(sourceOwner,player,UUID.randomUUID())));
        assertEquals(joined.originalSession(),capture.request().originalSession());assertEquals(1,captures.get());
    }
    @Test void pendingReadmissionIsCoalescedAndCannotCaptureUntilPublication()throws Exception {
        advance(21000);var gate=new CompletableFuture<Void>();flushGate.set(gate);int before=flushes.get();
        var first=main(()->provider.preparePurchase(sourceOwner,player,UUID.randomUUID()));
        // Barrier: submit admission explicitly so this test need not sleep for the SQL intent lookup.
        var pendingAdmission=main(()->provider.admit(player));
        var secondAdmission=main(()->provider.admit(player));assertSame(pendingAdmission,secondAdmission);
        assertEquals(before+1,flushes.get());assertEquals(0,captures.get());assertFalse(first.toCompletableFuture().isDone());
        flushGate.set(null);gate.complete(null);await(pendingAdmission);await(first);assertEquals(1,captures.get());
    }
    @Test void disconnectDuringReadmissionCannotCaptureOrResurrectSession()throws Exception {
        advance(21000);var gate=new CompletableFuture<Void>();flushGate.set(gate);
        var pending=main(()->provider.preparePurchase(sourceOwner,player,UUID.randomUUID()));
        main(()->provider.admit(player));
        main(()->{online.set(false);provider.retire(player);return null;});
        flushGate.set(null);gate.complete(null);
        assertThrows(ExecutionException.class,()->await(pending));
        assertTrue(main(()->provider.session(player).isEmpty()));assertEquals(0,captures.get());
    }
    @Test void obsoleteAdmissionCannotRemoveOrThawNewSessionAdmission()throws Exception {
        advance(21000);var oldGate=new CompletableFuture<Void>();flushGate.set(oldGate);
        var oldAdmission=main(()->provider.admit(player));
        // Retire schedules old-session release before the independent new admission.
        main(()->{flushGate.set(null);provider.retire(player);return null;});
        var newGate=new CompletableFuture<Void>();flushGate.set(newGate);
        var newAdmission=main(()->provider.admit(player));
        oldGate.complete(null);
        assertThrows(ExecutionException.class,()->await(oldAdmission));
        assertSame(newAdmission,main(()->provider.admit(player)));
        assertTrue(main(()->provider.session(player).isEmpty()));
        flushGate.set(null);newGate.complete(null);await(newAdmission);
        assertTrue(main(()->provider.session(player).isPresent()));
    }
    @Test void retryAfterIdleAndReconnectRetainsOriginalCaptureAndAward()throws Exception {
        var confirmation=purchase(UUID.randomUUID());
        var original=await(main(()->provider.preparePurchase(sourceOwner,player,confirmation.operationId())));
        var first=await(main(()->provider.grantConfirmedPurchase(sourceOwner,confirmation)));
        assertEquals(EnchantingXPProvider.State.APPLIED,first.state());
        advance(63000);main(()->{provider.retire(player);return null;});await(main(()->provider.admit(player)));
        assertNotEquals(original.request().originalSession(),main(()->provider.session(player).orElseThrow().originalSession()));
        assertEquals(original,await(main(()->provider.preparePurchase(sourceOwner,player,confirmation.operationId()))));
        assertEquals(first,await(main(()->provider.grantConfirmedPurchase(sourceOwner,confirmation))));
        assertEquals(1,captures.get());assertEquals(1,modifiers.get());assertEquals(original,first.receipt().originalAttribution());
    }

    @Test void expiredPurchasePreparationWaitsForInFlightNativeGrantInsteadOfRebindingItsCache()throws Exception {
        var confirmation=purchase(UUID.randomUUID());var proof=new CompletableFuture<EnchantingPurchaseAuthority.Verification>();proofGate.set(proof);
        var grant=main(()->provider.grantConfirmedPurchase(sourceOwner,confirmation));advance(21000);
        int before=flushes.get();var reachedExpiryCheck=new CountDownLatch(1);clockReadSignal.set(reachedExpiryCheck);
        var capture=main(()->provider.preparePurchase(sourceOwner,player,UUID.randomUUID()));
        assertTrue(reachedExpiryCheck.await(8,TimeUnit.SECONDS));main(()->null);clockReadSignal.set(null);
        assertEquals(before,flushes.get()); // No rebind while the earlier grant owns its pipeline.
        assertFalse(grant.toCompletableFuture().isDone());assertFalse(capture.toCompletableFuture().isDone());assertEquals(1,captures.get());
        proof.complete(confirmations.get(confirmation.operationId()));
        assertEquals(EnchantingXPProvider.State.APPLIED,await(grant).state());await(capture);
        assertEquals(2,captures.get());assertEquals(1,modifiers.get());
    }
}
