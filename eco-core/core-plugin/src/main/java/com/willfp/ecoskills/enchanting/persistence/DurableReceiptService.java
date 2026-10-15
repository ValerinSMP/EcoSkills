package com.willfp.ecoskills.enchanting.persistence;

import com.willfp.ecoskills.api.enchanting.*;
import java.time.Duration;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Provider-owned asynchronous receipt service. Construction neither migrates nor registers anything.
 * Bootstrap must pass the exact authorized plugin instance (not a caller-controlled name).
 * No DataSource/Connection/SQL escapes through the public capability.
 */
public final class DurableReceiptService implements EnchantingXPProvider, AutoCloseable {
    private final DurableEnchantingStore store;
    private final Object authorizedOwner;
    private final String scope;
    private final Supplier<Readiness> readiness;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final long timeoutMillis;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong generation=new AtomicLong();
    private EnchantingXPConsumer registeredConsumer;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    public DurableReceiptService(DurableEnchantingStore store,Object authorizedOwner,String scope,Supplier<Readiness> readiness) {
        this(store,authorizedOwner,scope,readiness,Duration.ofSeconds(5),64);
    }
    DurableReceiptService(DurableEnchantingStore store,Object owner,String scope,Supplier<Readiness> readiness,Duration timeout,int queueCapacity) {
        this.store=Objects.requireNonNull(store);authorizedOwner=Objects.requireNonNull(owner);this.scope=Objects.requireNonNull(scope);
        new ReceiptKey(scope,UUID.randomUUID(),UUID.randomUUID());this.readiness=Objects.requireNonNull(readiness);
        timeoutMillis=timeout.toMillis();if(timeoutMillis<1||timeoutMillis>15000||queueCapacity<1||queueCapacity>64)throw new IllegalArgumentException("Limits");
        workers=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queueCapacity),daemonFactory("EcoSkills-receipts-io"),new ThreadPoolExecutor.AbortPolicy());
        deadlines=new ScheduledThreadPoolExecutor(1,daemonFactory("EcoSkills-receipts-deadline"));deadlines.setRemoveOnCancelPolicy(true);
    }
    private static ThreadFactory daemonFactory(String name) {return work->{var t=new Thread(work,name);t.setDaemon(true);return t;};}
    @Override public Readiness readiness() {
        if(closed.get())return new Readiness(false,CONTRACT_VERSION,EnchantingXPConsumer.CONTRACT_VERSION,Set.of("closed"));
        return readiness.get();
    }
    @Override public synchronized Access registerConsumer(Plugin owner,EnchantingXPConsumer consumer) {
        Objects.requireNonNull(consumer);
        if(owner!=authorizedOwner||closed.get())throw new SecurityException("Unauthorized consumer");
        if(registeredConsumer!=null&&registeredConsumer!=consumer)throw new IllegalStateException("Consumer already registered");
        registeredConsumer=consumer;return authorizeConsumer(owner);
    }
    @Override public synchronized void unregisterConsumer(Plugin owner,EnchantingXPConsumer consumer) {
        if(owner!=authorizedOwner||registeredConsumer!=consumer)throw new SecurityException("Wrong consumer registration");
        revokeConsumer(owner);registeredConsumer=null;
    }
    /** Owner check uses reference identity. Caller supplies the plugin instance obtained from trusted bootstrap.
     * One consumer per scope; revocation invalidates ALL existing capabilities and queued work.
     */
    public synchronized Access authorizeConsumer(Object caller) {
        if(caller!=authorizedOwner||closed.get())throw new SecurityException("Unauthorized consumer");
        long captured=generation.incrementAndGet();
        return new Access() {
            private boolean valid(){return !closed.get()&&generation.get()==captured;}
            private boolean scopeMatches(String requested){return scope.equals(requested);}
            @Override public CompletionStage<Observation> lookupReceipt(ReceiptKey key) {
                Objects.requireNonNull(key);
                if(!scopeMatches(key.scope()))return CompletableFuture.completedStage(unavailable("scope_not_authorized"));
                return submit(()->valid()?store.lookupReceipt(key):unavailable("consumer_revoked"),unavailable("unavailable_or_timeout"));
            }
            @Override public CompletionStage<Observation> verifyDelivery(EnchantingXPConsumer.Delivery delivery) {
                Objects.requireNonNull(delivery);
                if(!scopeMatches(delivery.evidence().scope()))return CompletableFuture.completedStage(unavailable("scope_not_authorized"));
                return submit(()->valid()?store.verifyDelivery(delivery):unavailable("consumer_revoked"),unavailable("unavailable_or_timeout"));
            }
            @Override public CompletionStage<Ack> acknowledgeDelivery(EnchantingXPConsumer.Delivery delivery,EnchantingXPConsumer.Consumption committedResult) {
                Objects.requireNonNull(delivery);Objects.requireNonNull(committedResult);
                if(!scopeMatches(delivery.evidence().scope()))return CompletableFuture.completedStage(Ack.REJECTED);
                if(committedResult!=EnchantingXPConsumer.Consumption.APPLIED&&committedResult!=EnchantingXPConsumer.Consumption.ALREADY_APPLIED)return CompletableFuture.completedStage(Ack.REJECTED);
                return submit(()->valid()?store.acknowledgeDelivery(delivery,committedResult):Ack.UNAVAILABLE,Ack.UNKNOWN);
            }
        };
    }
    public synchronized void revokeConsumer(Object caller) {
        if(caller!=authorizedOwner)throw new SecurityException("Unauthorized consumer");generation.incrementAndGet();
    }
    private static Observation unavailable(String reason){return new Observation(State.UNAVAILABLE,reason,null);}
    private <T> CompletionStage<T> submit(Callable<T> operation,T uncertain) {
        var answer=new CompletableFuture<T>();
        // The queue slot remains occupied until the real operation exits, even after response timeout.
        // Only queued (not executing) tasks are removed on timeout. A running commit may remain uncertain.
        if(closed.get()){answer.complete(uncertain);return answer.minimalCompletionStage();}
        pending.add(answer);
        Runnable task=()->{
            if(answer.isDone()||closed.get()){answer.complete(uncertain);pending.remove(answer);return;}
            try{answer.complete(operation.call());}catch(Exception failure){answer.complete(uncertain);}finally{pending.remove(answer);}
        };
        try {
            workers.execute(task);
            var timer=deadlines.schedule(()->{answer.complete(uncertain);if(workers.remove(task))pending.remove(answer);},timeoutMillis,TimeUnit.MILLISECONDS);
            answer.whenComplete((value,failure)->timer.cancel(false));
        }catch(RejectedExecutionException rejected){workers.remove(task);pending.remove(answer);answer.complete(uncertain);}
        return answer.minimalCompletionStage();
    }
    /** Call before closing eco's pool; no caller-thread JDBC or unbounded await. */
    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;generation.incrementAndGet();
        workers.shutdownNow();deadlines.shutdownNow();
        for(var answer:pending)answer.completeExceptionally(new CancellationException("Provider closed; outcome may be uncertain"));
        pending.clear();
    }
}
