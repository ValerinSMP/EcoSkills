package com.willfp.ecoskills.enchanting.persistence;

import com.willfp.eco.core.data.handlers.*;
import com.willfp.eco.core.data.keys.*;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;

class FencedNativeProfileWriterTest {
    final JdbcNativeEnchantingGatewayTest fixture=new JdbcNativeEnchantingGatewayTest();
    final UUID player=UUID.randomUUID(),session=UUID.randomUUID();
    final DurableEnchantingStoreTest.Time time=new DurableEnchantingStoreTest.Time();
    <T> PersistentDataKey<T> key(String name,PersistentDataKeyType<T> type) {
        @SuppressWarnings("unchecked") PersistentDataKey<T> key=mock(PersistentDataKey.class);
        when(key.getKey()).thenReturn(new NamespacedKey("ecoskills",name));when(key.getType()).thenReturn(type);return key;
    }
    PersistentDataHandler handler(AtomicInteger fallbacks) {
        var handler=new PersistentDataHandler("test-fenced",1){public Set<UUID> getSavedUUIDs(){return Set.of();}};
        PersistentDataKeyType.INT.registerSerializer(handler,new DataTypeSerializer<Integer>() {
            public Integer readAsync(UUID id,PersistentDataKey<Integer> key){return null;}
            public void writeAsync(UUID id,PersistentDataKey<Integer> key,Integer value){fallbacks.incrementAndGet();}
        });
        PersistentDataKeyType.DOUBLE.registerSerializer(handler,new DataTypeSerializer<Double>() {
            public Double readAsync(UUID id,PersistentDataKey<Double> key){return null;}
            public void writeAsync(UUID id,PersistentDataKey<Double> key,Double value){fallbacks.incrementAndGet();}
        });return handler;
    }
    @Test void realHandlerKeepsOriginOrderAndStaleOrdinaryWritesCannotUndoAwardOrRewards()throws Exception {
        var pool=fixture.pool();var gateway=fixture.gateway();var store=new DurableEnchantingStore(pool,"eco_",time,gateway);store.migrate();
        var xp=key("enchanting_xp",PersistentDataKeyType.DOUBLE);var stat=key("wisdom",PersistentDataKeyType.INT);
        var fallbacks=new AtomicInteger();var reconcile=new AtomicInteger();var handler=handler(fallbacks);
        try {
            var writer=new FencedNativeProfileWriter(store,Map.of(xp,Double.class,stat,Integer.class),(id,reason)->reconcile.incrementAndGet());writer.install(handler);
            var fence=store.acquire("survival",player,session,60000);writer.bindReconciled(fence);
            var older=handler.prepareWrite(player,xp,10.0);var newer=handler.prepareWrite(player,xp,30.0);
            handler.writePrepared(newer);newer.completion().toCompletableFuture().get(3,TimeUnit.SECONDS);
            handler.writePrepared(older);older.completion().toCompletableFuture().get(3,TimeUnit.SECONDS);
            try(var c=pool.getConnection()){assertEquals(30,gateway.readForUpdate(c,player).xp());}
            var delayedXP=handler.prepareWrite(player,xp,999.0);var delayedStat=handler.prepareWrite(player,stat,0);
            writer.freeze(player);assertThrows(IllegalStateException.class,()->handler.prepareWrite(player,xp,55.0));
            var operation=UUID.randomUUID();store.award(new Award(operation,UUID.randomUUID(),"a".repeat(64),fence,250,new Attribution(UUID.randomUUID(),session,"original"),"fixture-confirmed-purchase"),50,l->100);
            handler.writePrepared(delayedXP);handler.writePrepared(delayedStat);
            assertThrows(ExecutionException.class,()->delayedXP.completion().toCompletableFuture().get(3,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,()->delayedStat.completion().toCompletableFuture().get(3,TimeUnit.SECONDS));
            assertEquals(2,reconcile.get());assertEquals(0,fallbacks.get());
            try(var c=pool.getConnection()){assertEquals(new NativeProgress(2,80),gateway.readForUpdate(c,player));assertEquals(4,fixture.reward(c));}
        }finally{handler.shutdown();}
    }
    @Test void transferredSessionAndUnregisteredScalarStayFenced()throws Exception {
        var pool=fixture.pool();var gateway=fixture.gateway();var store=new DurableEnchantingStore(pool,"eco_",time,gateway);store.migrate();
        var old=store.acquire("survival",player,session,1000);
        assertEquals(ScalarWriteResult.WRITTEN,store.writeCapturedScalar(old,"ecoskills:enchanting_xp",20.0,2));
        assertEquals(ScalarWriteResult.SUPERSEDED,store.writeCapturedScalar(old,"ecoskills:enchanting_xp",10.0,1));
        time.now.addAndGet(1001);var current=store.acquire("survival",player,UUID.randomUUID(),1000);
        assertEquals(ScalarWriteResult.STALE_FENCE,store.writeCapturedScalar(old,"ecoskills:enchanting_xp",999.0,3));
        assertThrows(java.sql.SQLException.class,()->store.writeCapturedScalar(current,"ecoskills:unregistered",1,1));
        assertThrows(java.sql.SQLException.class,()->store.writeCapturedScalar(current,"ecoskills:enchanting_xp",1,1));
        try(var c=pool.getConnection()){assertEquals(20,gateway.readForUpdate(c,player).xp());}
    }
}
