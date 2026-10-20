package com.willfp.ecoskills.skills;
import java.util.*;
import java.util.function.*;
/** One pending callback per UUID, no values, bounded admission and identity-fenced session retirement. */
public final class ViewUpdateQueue {
 private final Map<UUID,Set<String>> pending=new HashMap<>();
 private final int limit;
 private final Consumer<Runnable> scheduler;
 private final BiConsumer<UUID,Set<String>> flush;
 public ViewUpdateQueue(int limit,Consumer<Runnable> scheduler,BiConsumer<UUID,Set<String>> flush){this.limit=limit;this.scheduler=scheduler;this.flush=flush;}
 public void request(UUID uuid,Set<String> dependencies){
  Set<String> batch;
  synchronized(this){
   if(dependencies.isEmpty())return;
   batch=pending.get(uuid);
   if(batch!=null){batch.addAll(dependencies);return;}
   if(pending.size()>=limit)return;
   batch=new HashSet<>(dependencies);pending.put(uuid,batch);
  }
  final Set<String> identity=batch;
  try{scheduler.accept(()->{
   Set<String> snapshot;
   synchronized(this){
    if(pending.get(uuid)!=identity)return;
    pending.remove(uuid);snapshot=Set.copyOf(identity);
   }
   flush.accept(uuid,snapshot);
  });}catch(RuntimeException error){synchronized(this){pending.remove(uuid,identity);}throw error;}
 }
 public synchronized void retire(UUID uuid){pending.remove(uuid);}
 public synchronized void clear(){pending.clear();}
 public synchronized int size(){return pending.size();}
}
