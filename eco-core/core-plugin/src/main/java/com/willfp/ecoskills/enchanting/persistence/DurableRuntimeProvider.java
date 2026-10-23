package com.willfp.ecoskills.enchanting.persistence;

import com.willfp.eco.core.data.handlers.*;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.ecoskills.api.enchanting.*;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;

/** MAIN orchestration; bounded off-thread JDBC. Source authority is a real external adapter, never a flag.
 * The receipt delegate is unchanged; grants never emit a second legacy final-XP event.
 */
public final class DurableRuntimeProvider implements EnchantingXPProvider,AutoCloseable {
    public interface Host {
        boolean isMain();void executeMain(Runnable task);
        default long monotonicNanos(){return System.nanoTime();}
        ProfileMutationAccess access();
        default DurableEnchantingStore createStore(javax.sql.DataSource pool,String prefix){return new DurableEnchantingStore(pool,prefix,gateway(prefix));}
        Plugin purchaseOwner();Plugin consumerOwner();
        boolean online(UUID player);Collection<UUID> onlinePlayers();
        JdbcNativeEnchantingGateway gateway(String prefix);
        Map<PersistentDataKey<?>,Class<? extends Number>> keys();
        void publish(NativeSnapshot snapshot);
        Modified modify(UUID player,double baseXP);
        void levelCallback(Delivery delivery);
        void review(UUID player,String reason);
        int maxLevel();double requirement(int level);
    }
    public record Modified(double amount,boolean cancelled){public Modified{if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("Modified XP");}}
    private final Host host;private final String scope;
    private final ThreadPoolExecutor io;
    private final ScheduledThreadPoolExecutor timers;
    private final Semaphore admission=new Semaphore(64);
    private final Set<CompletableFuture<?>> responding=ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final NativeOperationQueue nativeQueue;
    private final Map<UUID,UUID> sessions=new HashMap<>();
    private final ConcurrentMap<UUID,AtomicBoolean> liveSessions=new ConcurrentHashMap<>();
    private final Map<UUID,Fence> fences=new HashMap<>();
    private final Map<UUID,CompletionStage<Void>> admitting=new HashMap<>();
    private DurableEnchantingStore store;private FencedNativeProfileWriter writer;private DurableReceiptService receipts;
    private EnchantingXPConsumer consumer;private Access consumerAccess;private EnchantingPurchaseAuthority authority;
    private volatile boolean initialized,closed,reloaded;
    private CompletionStage<Void> grantTail=CompletableFuture.completedFuture(null);
    public DurableRuntimeProvider(Host host,String scope) {
        this.host=Objects.requireNonNull(host);this.scope=scope;new ReceiptKey(scope,UUID.randomUUID(),UUID.randomUUID());main();
        io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"EcoSkills-awards-io");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        timers=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"EcoSkills-awards-deadlines");t.setDaemon(true);return t;});timers.setRemoveOnCancelPolicy(true);
        nativeQueue=new NativeOperationQueue(id->{if(initialized)admit(id);},host::review,host::monotonicNanos);
    }
    private void main(){if(!host.isMain())throw new IllegalStateException("MAIN required");}
    private <T>CompletionStage<T> onMain(Supplier<T> work){var f=new CompletableFuture<T>();try{host.executeMain(()->{try{if(closed)throw new IllegalStateException("Provider closed");f.complete(work.get());}catch(Throwable e){f.completeExceptionally(e);}});}catch(RuntimeException e){f.completeExceptionally(e);}return f;}
    private <T>CompletionStage<T> sql(Callable<T> work){
        var f=new CompletableFuture<T>();if(closed)return CompletableFuture.failedStage(new IllegalStateException("Provider closed"));pending.add(f);
        try{io.execute(()->{try{if(closed)throw new IllegalStateException("Provider closed");f.complete(work.call());}catch(Throwable e){f.completeExceptionally(e);}finally{pending.remove(f);}});}catch(RejectedExecutionException e){pending.remove(f);f.completeExceptionally(e);}return f;
    }
    private <T>CompletionStage<T> boundedResponse(CompletionStage<T> work,Supplier<T> uncertain){
        if(closed)return CompletableFuture.failedStage(new IllegalStateException("Provider closed"));
        var response=new CompletableFuture<T>();responding.add(response);response.whenComplete((v,e)->responding.remove(response));var timer=timers.schedule(()->response.complete(uncertain.get()),15,TimeUnit.SECONDS);
        work.whenComplete((v,e)->{timer.cancel(false);if(e!=null)response.completeExceptionally(e);else response.complete(v);});return response.minimalCompletionStage();
    }
    private void owner(Plugin candidate,boolean source){main();if(candidate==null||candidate!=(source?host.purchaseOwner():host.consumerOwner()))throw new SecurityException("Wrong owning plugin instance");}
    /** Enabled only by explicit configuration. No DB work in the constructor or MAIN factory. */
    public CompletionStage<Void> initialize(){
        main();if(!host.access().supported())return CompletableFuture.failedStage(new UnsupportedOperationException("Initialized MySQL handler required"));
        nativeQueue.install();
        return host.access().flushAll().thenCompose(v->onMain(()->{
            host.access().install((pool,prefix)->{
                store=host.createStore(pool,prefix);
                writer=new FencedNativeProfileWriter(store,host.keys(),(id,reason)->host.executeMain(()->{host.review(id,reason);admit(id);}));
                return writer;
            });return null;
        })).thenCompose(v->sql(()->{store.migrate();store.bindScope(scope);return null;})).thenCompose(v->onMain(()->{
            initialized=true;
            // Receipt access can be installed lazily when vPets registers after EcoSkills enabled.
            var players=new HashSet<UUID>(host.onlinePlayers());players.addAll(nativeQueue.pendingPlayers());
            for(UUID id:players)admit(id);
            recoverInternal(32);
            return null;
        }));
    }
    @Override public Readiness readiness(){
        main();var blockers=new HashSet<String>();if(closed)blockers.add("closed");if(!initialized)blockers.add("native_backend_initializing_or_unsupported");
        if(reloaded)blockers.add("configuration_changed_requires_restart");if(authority==null)blockers.add("purchase_authority_not_registered");if(consumer==null)blockers.add("consumer_not_registered");
        return new Readiness(blockers.isEmpty(),CONTRACT_VERSION,EnchantingXPConsumer.CONTRACT_VERSION,blockers);
    }
    @Override public Access registerConsumer(Plugin owner,EnchantingXPConsumer value){
        owner(owner,false);if(!initialized)throw new IllegalStateException("Backend not initialized");if(consumer!=null&&consumer!=value)throw new IllegalStateException("Consumer already registered");
        if(receipts==null)receipts=new DurableReceiptService(store,owner,scope,this::readiness);
        consumer=Objects.requireNonNull(value);consumerAccess=receipts.registerConsumer(owner,value);recoverInternal(32);return consumerAccess;
    }
    @Override public void unregisterConsumer(Plugin owner,EnchantingXPConsumer value){owner(owner,false);if(consumer!=value)throw new SecurityException("Wrong consumer");receipts.unregisterConsumer(owner,value);consumer=null;consumerAccess=null;}
    @Override public void registerPurchaseAuthority(Plugin owner,EnchantingPurchaseAuthority value){owner(owner,true);if(authority!=null&&authority!=value)throw new IllegalStateException("Authority already registered");authority=Objects.requireNonNull(value);}
    @Override public void unregisterPurchaseAuthority(Plugin owner,EnchantingPurchaseAuthority value){owner(owner,true);if(authority!=value)throw new SecurityException("Wrong authority");authority=null;}
    @Override public Optional<Session> session(UUID id){main();return initialized&&host.online(id)&&nativeQueue.ready(id)&&sessions.containsKey(id)?Optional.of(new Session(id,sessions.get(id),scope)):Optional.empty();}
    public CompletionStage<Void> admit(UUID id){
        main();if(!initialized||closed)return CompletableFuture.failedStage(new IllegalStateException("Native backend unavailable"));
        var existing=admitting.get(id);if(existing!=null)return existing;
        UUID session=sessions.computeIfAbsent(id,k->UUID.randomUUID());var live=liveSessions.computeIfAbsent(session,k->new AtomicBoolean(true));long epoch=nativeQueue.freeze(id);writer.freeze(id);
        var result=new CompletableFuture<Void>();admitting.put(id,result);
        host.access().flush(id).thenCompose(v->sql(()->{
            if(!live.get())throw new IllegalStateException("Session retired before admission");
            var fence=store.acquire(scope,id,session,60000);if(fence==null)throw new IllegalStateException("Other session lease active");
            if(!live.get()){store.releaseSession(scope,id,session);throw new IllegalStateException("Session retired during admission");}
            var snapshot=store.snapshot(fence);if(snapshot==null)throw new IllegalStateException("Snapshot fence changed");return snapshot;
        })).thenCompose(snapshot->onMain(()->{
            if(!session.equals(sessions.get(id)))throw new IllegalStateException("Session replaced");
            host.publish(snapshot);writer.bindReconciled(snapshot.fence());fences.put(id,snapshot.fence());nativeQueue.resume(id,epoch);return null;
        })).whenComplete((v,e)->host.executeMain(()->{
            boolean current=admitting.remove(id,result)&&session.equals(sessions.get(id));
            if(e!=null){
                if(current){nativeQueue.retryAdmission(id);var fence=fences.get(id);host.review(id,"native_admission_pending:"+nativeQueue.diagnostic(id)+",fenceRevision="+(fence==null?"absent":fence.revision())+",cause="+e.getClass().getSimpleName()+":"+e.getMessage());}
                result.completeExceptionally(e);
            }else{result.complete(null);if(current)recoverInternal(8);}
        }));return result;
    }
    public void joined(UUID id){main();if(initialized){retire(id);admit(id);}}
    public void retire(UUID id){
        main();admitting.remove(id);UUID original=sessions.remove(id);if(original!=null){var live=liveSessions.remove(original);if(live!=null)live.set(false);}fences.remove(id);writerIfPresentFreeze(id);nativeQueue.retire(id);
        if(initialized&&original!=null)host.access().flush(id).thenCompose(v->sql(()->store.releaseSession(scope,id,original)));
    }
    private void writerIfPresentFreeze(UUID id){if(writer!=null)writer.detach(id);}
    @Override public CompletionStage<EnchantingXPConsumer.Attribution> preparePurchase(Plugin owner,UUID player,UUID operation){
        owner(owner,true);if(!readiness().ready())return CompletableFuture.failedStage(new IllegalStateException("Provider not ready"));
        if(!admission.tryAcquire())return CompletableFuture.failedStage(new RejectedExecutionException("Provider admission full"));
        var flow=sql(()->store.captured(scope,operation)).thenCompose(existing->{
            if(existing!=null){if(!existing.request().playerId().equals(player))return CompletableFuture.failedStage(new IllegalStateException("Capture player conflict"));return CompletableFuture.completedStage(existing);}
            return sql(()->store.intent(scope,operation)).thenCompose(intent->onMain(()->prepareAttributionRequest(player,operation,intent)).thenCompose(Function.identity())).thenCompose(request->sql(()->store.retainIntent(request))).thenCompose(request->onMain(()->consumer.capture(request)).thenCompose(Function.identity())).thenCompose(capture->sql(()->{
                if(!capture.request().playerId().equals(player)||!capture.request().scope().equals(scope)||!capture.request().operationId().equals(operation))throw new IllegalStateException("Consumer capture conflict");
                var intent=store.intent(scope,operation);if(!capture.request().equals(intent))throw new IllegalStateException("Original session conflict");return store.retainCapture(capture);
            }));
        });flow.whenComplete((v,e)->admission.release());
        return boundedResponse(flow,()->null).thenCompose(value->value==null?CompletableFuture.failedStage(new TimeoutException("Capture uncertain; reuse operation")):CompletableFuture.completedStage(value));
    }
    @Override public CompletionStage<EnchantingPurchaseQuote> quotePurchase(Plugin owner,EnchantingPurchaseQuote.Context context){
        owner(owner,true);Objects.requireNonNull(context);
        if(!readiness().ready()||!scope.equals(context.scope())||!context.sessionId().equals(sessions.get(context.playerId()))||!nativeQueue.ready(context.playerId()))return CompletableFuture.failedStage(new IllegalStateException("Quote session/provider unavailable"));
        if(!admission.tryAcquire())return CompletableFuture.failedStage(new RejectedExecutionException("Quote admission full"));
        var original=authority;
        CompletionStage<EnchantingPurchaseQuote> flow;
        try{flow=original.quotePurchase(context);}catch(RuntimeException|LinkageError failure){admission.release();return CompletableFuture.failedStage(failure);}
        if(flow==null){admission.release();return CompletableFuture.failedStage(new IllegalStateException("Authority quote absent"));}
        var checked=flow.thenCompose(quote->onMain(()->{
            if(authority!=original||closed||reloaded||quote==null||!context.sessionId().equals(sessions.get(context.playerId()))||!quote.context().equals(context))throw new IllegalArgumentException("Quote authority/context changed");return quote;
        }));
        checked.whenComplete((v,e)->admission.release());
        return boundedResponse(checked,()->null).thenCompose(quote->quote==null?CompletableFuture.failedStage(new TimeoutException("Quote uncertain; reuse original operation")):CompletableFuture.completedStage(quote));
    }
    /** A fresh purchase waits for real admission; retries keep their durable original attribution. MAIN only. */
    private CompletionStage<EnchantingXPConsumer.AttributionRequest> prepareAttributionRequest(UUID player,UUID operation,EnchantingXPConsumer.AttributionRequest intent){
        main();
        if(intent!=null){
            if(!intent.playerId().equals(player))return CompletableFuture.failedStage(new IllegalStateException("Intent player conflict"));
            return CompletableFuture.completedStage(intent);
        }
        if(!host.online(player))return CompletableFuture.failedStage(new IllegalStateException("Player offline before purchase admission"));
        if(!nativeQueue.ready(player)&&!grantTail.toCompletableFuture().isDone()){
            UUID original=sessions.get(player);
            return grantTail.handle((v,e)->null).thenCompose(v->onMain(()->{
                if(original!=null&&!original.equals(sessions.get(player)))throw new IllegalStateException("Player session changed while waiting for native grant");
                return prepareAttributionRequest(player,operation,null);
            })).thenCompose(Function.identity());
        }
        CompletionStage<Void> ready=nativeQueue.ready(player)?CompletableFuture.completedStage(null):admit(player);
        UUID expected=sessions.get(player);
        return ready.thenCompose(v->onMain(()->{
            if(!host.online(player)||expected==null||!expected.equals(sessions.get(player)))throw new IllegalStateException("Player session changed during purchase admission");
            var current=session(player).orElseThrow(()->new IllegalStateException("Player admission unavailable before capture"));
            return new EnchantingXPConsumer.AttributionRequest(operation,player,scope,current.originalSession());
        }));
    }
    private static final class Refusal extends RuntimeException {final Observation observation;Refusal(State state,String reason){observation=new Observation(state,reason,null);}}
    private Observation failure(Throwable error,String fallback){for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof Refusal refusal)return refusal.observation;return new Observation(State.UNKNOWN,fallback,null);}
    private Observation unavailable(String reason){return new Observation(State.UNAVAILABLE,reason,null);}
    @Override public CompletionStage<Observation> grantConfirmedPurchase(Plugin owner,EnchantingPurchaseAuthority.Confirmation confirmation){
        owner(owner,true);if(!readiness().ready()||!scope.equals(confirmation.scope()))return CompletableFuture.completedStage(unavailable("not_ready_or_scope"));
        if(!admission.tryAcquire())return CompletableFuture.completedStage(unavailable("admission_full"));
        var source=authority;var result=new CompletableFuture<Observation>();
        // Serialize complete grant pipelines, not only isolated SQL pieces. Bounded by admission64.
        grantTail=grantTail.handle((v,e)->null).thenCompose(v->sql(()->store.lookup(scope,confirmation.operationId()))).thenCompose(known->{
            if(known.status()==Status.APPLIED)return sql(()->{
                var previous=store.lookupReceipt(new ReceiptKey(scope,confirmation.operationId(),known.awardId()));
                if(previous.state()==State.APPLIED&&!previous.receipt().evidence().deliveryReceiptId().equals(confirmation.deliveryReceiptId()))return new Observation(State.PAYLOAD_CONFLICT,"original_confirmation_changed",null);
                return previous;
            });
            return onMain(()->source.verifyConfirmedPurchase(confirmation)).thenCompose(Function.identity())
            .thenCompose(proof->onMain(()->{
                if(authority!=source||reloaded||closed)throw new IllegalStateException("Authority/configuration changed");
                if(proof==null)throw new Refusal(State.UNKNOWN,"source_verification_absent");
                if(proof.state()!=EnchantingPurchaseAuthority.State.CONFIRMED)throw new Refusal(switch(proof.state()){case REJECTED->State.REJECTED;case NOT_COMMITTED->State.PENDING;default->State.UNKNOWN;},"purchase_not_confirmed");
                var evidence=proof.evidence();var captured=proof.originalAttribution();
                if(!evidence.scope().equals(scope)||!evidence.operationId().equals(confirmation.operationId())||!evidence.deliveryReceiptId().equals(confirmation.deliveryReceiptId())||
                    !captured.request().operationId().equals(evidence.operationId())||!captured.request().playerId().equals(evidence.playerId())||!captured.request().originalSession().equals(evidence.sessionId())||!captured.request().scope().equals(scope))throw new IllegalArgumentException("Confirmed purchase binding conflict");
                return proof;
            })).thenCompose(proof->sql(()->{
                if(!proof.originalAttribution().equals(store.captured(scope,confirmation.operationId())))throw new IllegalStateException("Original provider capture conflict");return proof.evidence();
            })).thenCompose(this::grant);
        }).handle((observation,error)->{if(error!=null)result.complete(failure(error,"grant_unknown_reuse_confirmation"));else result.complete(observation);return (Void)null;});
        grantTail.whenComplete((v,e)->admission.release());
        return boundedResponse(result,()->new Observation(State.UNKNOWN,"response_timeout_reuse_confirmation",null));
    }
    @Override public CompletionStage<Observation> lookupPurchaseReceipt(Plugin owner,EnchantingPurchaseAuthority.Confirmation original){
        owner(owner,true);Objects.requireNonNull(original);
        if(authority==null)return CompletableFuture.completedStage(unavailable("purchase_authority_not_registered"));
        if(!scope.equals(original.scope()))return CompletableFuture.completedStage(unavailable("scope_not_authorized"));
        if(!initialized||closed)return CompletableFuture.completedStage(unavailable("native_backend_unavailable"));
        if(!admission.tryAcquire())return CompletableFuture.completedStage(unavailable("admission_full"));
        var registered=authority;
        var read=sql(()->store.lookupPurchaseReceipt(original)).thenCompose(observed->onMain(()->
            authority==registered&&owner==host.purchaseOwner()?observed:unavailable("purchase_authority_changed")))
            .exceptionally(error->new Observation(State.UNKNOWN,"purchase_receipt_read_uncertain",null));
        read.whenComplete((v,e)->admission.release());
        return boundedResponse(read,()->new Observation(State.UNKNOWN,"purchase_receipt_read_timeout",null));
    }
    private CompletionStage<Observation> grant(EnchantingPurchaseEvidence evidence){
        var key=new ReceiptKey(scope,evidence.operationId(),evidence.awardId());
        return sql(()->store.lookupReceipt(key)).thenCompose(previous->{
            if(previous.state()==State.APPLIED){if(!previous.receipt().evidence().equals(evidence))return CompletableFuture.completedStage(new Observation(State.PAYLOAD_CONFLICT,"confirmed_payload_changed",null));return CompletableFuture.completedStage(previous);}
            if(previous.state()!=State.NOT_FOUND)return CompletableFuture.completedStage(previous);
            return onMain(()->admit(evidence.playerId())).thenCompose(Function.identity()).thenCompose(v->onMain(()->{
                UUID id=evidence.playerId();UUID current=sessions.get(id);long epoch=nativeQueue.freeze(id);writer.freeze(id);return new GrantContext(epoch,current,Objects.requireNonNull(fences.get(id),"Native fence absent"));
            })).thenCompose(context->host.access().flush(evidence.playerId()).thenCompose(v->sql(()->store.beginCalculation(evidence))).thenCompose(calculation->{
                if(calculation.phase().equals("PREPARED"))return CompletableFuture.completedStage(calculation.modifiedXP());
                if(!calculation.phase().equals("CALCULATING"))return CompletableFuture.failedStage(new Refusal(switch(calculation.phase()){case "CONFLICT"->State.PAYLOAD_CONFLICT;case "REVIEW","REJECTED"->State.REJECTED;default->State.PENDING;},"calculation_"+calculation.phase().toLowerCase(java.util.Locale.ROOT)));
                return onMain(()->{if(!evidence.sessionId().equals(sessions.get(evidence.playerId()))||!host.online(evidence.playerId()))throw new IllegalStateException("Original modifier callback session absent; REVIEW");return host.modify(evidence.playerId(),EnchantingPurchaseXP.baseXP(evidence));}).thenCompose(modified->sql(()->{
                    if(!store.finishCalculation(evidence,calculation.token(),modified.amount(),modified.cancelled()))throw new IllegalStateException("Calculation attempt uncertain");
                    if(modified.cancelled())throw new Refusal(State.REJECTED,"native_xp_event_cancelled");return modified.amount();
                }));
            }).thenCompose(amount->sql(()->{
                var f=context.fence();
                var captured=store.captured(scope,evidence.operationId());
                // Award attribution always retains the ORIGINAL purchase session, not the current node's session.
                // Current write lease protects native state; ORIGINAL capture/session remains bound below.
                store.award(new Award(evidence.operationId(),evidence.awardId(),evidence.payloadHash(),f,amount,
                    new Attribution(captured.captureId(),captured.request().originalSession(),captured.opaqueToken()),PurchaseEvidenceCodec.encode(evidence)),host.maxLevel(),level->host.requirement((int)level));
                return store.lookupReceipt(key);
            })).handle((observation,error)->new AbstractMap.SimpleImmutableEntry<>(observation,error)).thenCompose(outcome->onMain(()->reconcileAfterGrant(evidence.playerId(),context.epoch())).thenCompose(Function.identity()).handle((v,error)->{
                if(outcome.getValue()!=null)return failure(outcome.getValue(),"grant_uncertain_reuse_confirmation");if(error!=null)return failure(error,"publication_uncertain_reuse_confirmation");return outcome.getKey();
            })));
        });
    }
    private record GrantContext(long epoch,UUID session,Fence fence) {}
    private CompletionStage<Void> reconcileAfterGrant(UUID id,long epoch){
        main();var current=sessions.get(id);if(current==null)return CompletableFuture.failedStage(new IllegalStateException("Session retired; receipt remains durable"));
        return sql(()->{var snapshot=store.snapshotCurrent(scope,id,current);if(snapshot==null)throw new IllegalStateException("Publication session expired or retired");return snapshot;})
            .thenCompose(snapshot->onMain(()->{if(!current.equals(sessions.get(id)))throw new IllegalStateException("Session changed");host.publish(snapshot);writer.bindReconciled(snapshot.fence());fences.put(id,snapshot.fence());nativeQueue.resume(id,epoch);recoverInternal(32);return (Void)null;})).whenComplete((value,error)->{
                if(error!=null&&!closed)host.executeMain(()->{nativeQueue.retryAdmission(id);host.review(id,"native_publication_pending");});
            });
    }
    @Override public CompletionStage<Integer> recoverDeliveries(Plugin owner,int limit){owner(owner,false);return recoverInternal(limit);}
    private CompletionStage<Integer> recoverInternal(int limit){
        main();if(limit<1||limit>32)throw new IllegalArgumentException("Recovery bound");if(!initialized||closed)return CompletableFuture.completedStage(0);
        var target=consumer;var access=consumerAccess;var localSessions=Set.copyOf(sessions.values());
        return sql(()->{store.reconcileUncertainCalculations(scope,limit);store.reconcileUncertainLevelCallbacks(scope,limit);var list=new ArrayList<Delivery>();
            list.addAll(store.claimCallbacks(scope,localSessions,limit,30000));
            if(target!=null&&access!=null)list.addAll(store.claim(scope,Kind.FINAL_XP,limit,30000));return List.copyOf(list);})
            .thenCompose(deliveries->{CompletionStage<Integer> flow=CompletableFuture.completedStage(0);for(var delivery:deliveries)flow=flow.thenCompose(count->deliver(delivery,target,access).thenApply(v->count+1));return flow;});
    }
    private CompletionStage<Void> deliver(Delivery delivery,EnchantingXPConsumer target,Access access){
        if(delivery.kind()==Kind.LEVEL_EFFECT)return onMain(()->{
            if(reloaded||!host.online(delivery.player())||!delivery.originalSession().equals(sessions.get(delivery.player())))return false;
            host.levelCallback(delivery);return true;
        }).handle((success,error)->error==null&&Boolean.TRUE.equals(success)).thenCompose(success->sql(()->{if(success)store.acknowledge(delivery);else store.review(delivery);return null;}));
        try {
            var evidence=PurchaseEvidenceCodec.decode(delivery.confirmedEvidence());
            var attribution=new EnchantingXPConsumer.Attribution(delivery.attribution().captureId(),new EnchantingXPConsumer.AttributionRequest(delivery.operationId(),delivery.player(),delivery.scope(),delivery.originalSession()),delivery.attribution().opaqueToken());
            var typed=new EnchantingXPConsumer.Delivery(evidence,delivery.awardId(),delivery.finalXP(),attribution,delivery.claim(),delivery.receiptPayloadHash());
            return onMain(()->{if(consumer!=target)throw new IllegalStateException("Consumer changed");return target.consume(typed);}).thenCompose(Function.identity()).thenCompose(result->{
                if(result==EnchantingXPConsumer.Consumption.APPLIED||result==EnchantingXPConsumer.Consumption.ALREADY_APPLIED)return access.acknowledgeDelivery(typed,result).thenApply(v->(Void)null);
                if(result==EnchantingXPConsumer.Consumption.REVIEW||result==EnchantingXPConsumer.Consumption.PAYLOAD_CONFLICT)return sql(()->{store.review(delivery);return null;});
                return CompletableFuture.completedStage(null);
            }).exceptionally(error->null); // Uncertain/lost consumer result stays RUNNING, never success-acked.
        }catch(java.io.IOException|RuntimeException corrupt){return sql(()->{store.review(delivery);return null;});}
    }
    public void participantDisabled(Plugin participant){
        main();if(participant==host.purchaseOwner())authority=null;
        if(participant==host.consumerOwner()){if(receipts!=null)receipts.revokeConsumer(participant);consumer=null;consumerAccess=null;}
    }
    public void configurationChanged(){main();if(initialized)reloaded=true;}
    @Override public void close(){main();if(closed)return;closed=true;authority=null;consumer=null;if(receipts!=null)receipts.close();nativeQueue.close();liveSessions.values().forEach(flag->flag.set(false));liveSessions.clear();
        for(var stage:List.copyOf(admitting.values()))stage.toCompletableFuture().completeExceptionally(new CancellationException("Provider closed"));admitting.clear();
        io.shutdownNow();timers.shutdownNow();for(var f:pending)f.completeExceptionally(new CancellationException("Provider closed; reconcile receipt"));pending.clear();for(var f:responding)f.completeExceptionally(new CancellationException("Provider closed; reconcile original operation"));responding.clear();}
}
