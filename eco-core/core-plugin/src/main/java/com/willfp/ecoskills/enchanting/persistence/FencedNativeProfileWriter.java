package com.willfp.ecoskills.enchanting.persistence;

import com.willfp.eco.core.data.handlers.*;
import com.willfp.eco.core.data.keys.*;
import java.sql.SQLException;
import java.util.*;
import java.util.function.BiConsumer;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;

/** Bridge to eco's REAL profile writer. Opt-in, no registration/session creation in constructor.
 * Uses only actual registered native scalar keys supplied by EcoSkills. No defaults/read on capture.
 * This bridge fences SQL, but does NOT itself queue whole domain mutations or project a committed
 * award into loaded profiles. Bootstrap MUST keep awarding unavailable until those are bound.
 */
public final class FencedNativeProfileWriter implements ProfileWriteBarrier {
    private final DurableEnchantingStore store;
    private final Map<PersistentDataKey<?>,Class<? extends Number>> owned;
    private final BiConsumer<UUID,String> reconcile;
    private final Thread main;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private static final class Session {
        final Fence fence;long sequence;volatile boolean frozen;
        Session(Fence fence){this.fence=fence;}
    }
    public FencedNativeProfileWriter(DurableEnchantingStore store,Map<PersistentDataKey<?>,Class<? extends Number>> keys,BiConsumer<UUID,String> reconcile) {
        this.store=Objects.requireNonNull(store);this.reconcile=Objects.requireNonNull(reconcile);main=Thread.currentThread();
        if(keys.isEmpty()||keys.size()>1024)throw new IllegalArgumentException("Owned key count");
        for(var entry:keys.entrySet()) {
            var key=entry.getKey();var type=entry.getValue();
            if(!key.getKey().getNamespace().equals("ecoskills")||key.isLocal()||
                !(type==Integer.class&&key.getType()==PersistentDataKeyType.INT||type==Double.class&&key.getType()==PersistentDataKeyType.DOUBLE))throw new IllegalArgumentException("Native key ownership/type");
        }
        owned=Map.copyOf(keys);
    }
    private void main(){if(Thread.currentThread()!=main)throw new IllegalStateException("Native writer origin requires MAIN");}
    /** Exact initialized handler; startup installs before admissions, never swaps/removes a live binding. */
    public void install(PersistentDataHandler handler){main();handler.installProfileWriteBarrier(this);}
    /** Only after native cache has been reconciled to this committed fence/revision on MAIN. */
    public void bindReconciled(Fence fence) {
        main();Objects.requireNonNull(fence);
        if(!sessions.containsKey(fence.player())&&sessions.size()>=512)throw new IllegalStateException("Session admission full");
        var previous=sessions.get(fence.player());
        if(previous!=null&&!previous.frozen)throw new IllegalStateException("Freeze domain mutations before changing fence");
        var replacement=new Session(fence);
        if(previous!=null&&previous.fence.equals(fence))replacement.sequence=previous.sequence;
        sessions.put(fence.player(),replacement);
    }
    /** Caller must retain/queue COMPLETE native operations, not only their scalar absolute values. */
    public void freeze(UUID player){main();var session=sessions.get(player);if(session!=null)session.frozen=true;}
    public void detach(UUID player){main();var session=sessions.remove(player);if(session!=null)session.frozen=true;}
    @Override public Runnable capture(UUID player,PersistentDataKey<?> key,Object value) {
        var type=owned.get(key);if(type==null)return null;main();
        var session=sessions.get(player);
        if(session==null||session.frozen)throw new IllegalStateException("Native profile unavailable/frozen; retain original operation");
        if(!type.isInstance(value)||!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Native scalar value");
        long sequence=session.sequence=Math.addExact(session.sequence,1);var origin=session.fence;var name=key.getKey().toString();
        return ()->{
            // Do not inspect current session/cache on this I/O thread or stamp at flush time.
            try {
                var outcome=store.writeCapturedScalar(origin,name,(Number)value,sequence);
                if(outcome==ScalarWriteResult.STALE_FENCE){session.frozen=true;reconcile.accept(player,"stale_native_origin");throw new IllegalStateException("Stale native write fenced");}
            }catch(SQLException uncertain){session.frozen=true;reconcile.accept(player,"native_write_unknown");throw new IllegalStateException("Reconcile durable native state; do not replay blindly",uncertain);}
        };
    }
}
