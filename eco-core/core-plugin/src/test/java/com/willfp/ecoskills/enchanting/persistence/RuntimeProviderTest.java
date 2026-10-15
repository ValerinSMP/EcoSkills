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
            public CompletionStage<Void>flush(UUID p){assertSame(mainThread.get(),Thread.currentThread());return writer.awaitProfileWrites(p);}
            public CompletionStage<Void>flushAll(){return writer.awaitProfileWrites(null);}
            public boolean publish(UUID p,Set<PersistentDataKey<?>>owned,Map<PersistentDataKey<?>,Object> values){assertSame(mainThread.get(),Thread.currentThread());cache.clear();values.forEach((k,v)->cache.put(k.getKey().toString(),(Number)v));return true;}
        };
        provider=main(()->new DurableRuntimeProvider(new DurableRuntimeProvider.Host(){
            public boolean isMain(){return Thread.currentThread()==mainThread.get();}
            public void executeMain(Runnable task){if(isMain())task.run();else main.execute(task);}
            public ProfileMutationAccess access(){return mutation;}
            public Plugin purchaseOwner(){return sourceOwner;}public Plugin consumerOwner(){return consumerOwner;}
            public boolean online(UUID p){return player.equals(p);}public Collection<UUID>onlinePlayers(){return List.of();}
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
            provider.registerPurchaseAuthority(sourceOwner,confirmation->CompletableFuture.completedStage(confirmations.getOrDefault(confirmation.operationId(),new EnchantingPurchaseAuthority.Verification(EnchantingPurchaseAuthority.State.NOT_COMMITTED,null,null,"not_confirmed"))));
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
}
