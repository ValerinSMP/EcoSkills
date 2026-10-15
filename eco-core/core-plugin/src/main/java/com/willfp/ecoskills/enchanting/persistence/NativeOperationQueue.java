package com.willfp.ecoskills.enchanting.persistence;
import java.util.*;
import java.util.function.*;
/** MAIN-only complete-operation queue. No scalar-value rebasing and no callback replay on uncertainty. */
public final class NativeOperationQueue {
    private static NativeOperationQueue installed;
    private final Thread main=Thread.currentThread();
    private final Map<UUID,State> states=new HashMap<>();
    private final Consumer<UUID> admission;
    private final BiConsumer<UUID,String> review;
    private boolean closed;
    private static final class State { boolean frozen=true,requested;long epoch,validUntil;final ArrayDeque<Runnable> operations=new ArrayDeque<>(); }
    public NativeOperationQueue(Consumer<UUID> admission,BiConsumer<UUID,String> review){this.admission=admission;this.review=review;}
    public void install(){main();if(installed!=null)throw new IllegalStateException("Native queue already installed");installed=this;}
    private void main(){if(Thread.currentThread()!=main)throw new IllegalStateException("Native operations require MAIN");}
    public static boolean active(){return installed!=null;}
    public static boolean defer(UUID player,Runnable operation){return installed!=null&&installed.enqueue(player,operation);}
    private boolean enqueue(UUID player,Runnable operation){
        main();if(closed)throw new IllegalStateException("Native queue closed");
        var state=states.get(player);
        if(state==null){if(states.size()>=512)throw new IllegalStateException("Native session capacity");state=new State();states.put(player,state);}
        if(!state.frozen&&System.nanoTime()<state.validUntil)return false;
        state.frozen=true;
        if(state.operations.size()>=64)throw new IllegalStateException("Native operation queue full; retry original request");
        state.operations.add(Objects.requireNonNull(operation));
        if(!state.requested){state.requested=true;admission.accept(player);}return true;
    }
    public long freeze(UUID player){main();var state=states.computeIfAbsent(player,id->new State());state.frozen=true;state.requested=true;return ++state.epoch;}
    public void resume(UUID player,long epoch){
        main();var state=states.get(player);if(state==null||state.epoch!=epoch||closed)return;
        state.frozen=false;state.requested=false;state.validUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while(!state.frozen&&!state.operations.isEmpty()) {
            var operation=state.operations.removeFirst(); // Dequeue BEFORE execution; never repeat an uncertain callback.
            try{operation.run();}catch(RuntimeException failure){state.frozen=true;state.requested=true;review.accept(player,"native_operation_uncertain");break;}
        }
    }
    public List<UUID> pendingPlayers(){main();return List.copyOf(states.keySet());}
    public boolean ready(UUID player){main();var state=states.get(player);return state!=null&&!state.frozen&&System.nanoTime()<state.validUntil;}
    public void retryAdmission(UUID player){main();var state=states.get(player);if(state!=null)state.requested=false;}
    public void retire(UUID player){main();var state=states.remove(player);if(state!=null&&!state.operations.isEmpty())review.accept(player,"session_retired_with_queued_operations:"+state.operations.size());}
    public void close(){main();closed=true;for(var id:List.copyOf(states.keySet()))retire(id);if(installed==this)installed=null;}
    /** Used by minimal entry guards grafted into the existing native methods, including foreign XP bridge.
     * Only the exact already-loaded native method/arguments are retained, before gain events or mutation.
     */
    public static boolean deferInvocation(UUID player,Object receiver,String name,String descriptor,Object[] args) {
        if(installed==null)return false;
        String owner=receiver instanceof Class<?> c?c.getName():receiver.getClass().getName();
        if(!Set.of("com.willfp.ecoskills.skills.SkillLevelMap","com.willfp.ecoskills.util.LevelMap","com.willfp.ecoskills.api.EcoSkillsAPI").contains(owner))throw new IllegalArgumentException("Native owner");
        return defer(player,()->{
            try {
                Class<?> type=receiver instanceof Class<?> c?c:receiver.getClass();
                var signature=java.lang.invoke.MethodType.fromMethodDescriptorString(descriptor,type.getClassLoader());
                type.getMethod(name,signature.parameterArray()).invoke(receiver instanceof Class<?>?null:receiver,args);
            }catch(java.lang.reflect.InvocationTargetException failure){throw new IllegalStateException("Native operation uncertain",failure.getCause());}
            catch(ReflectiveOperationException failure){throw new IllegalStateException("Native method unavailable",failure);}
        });
    }
}
