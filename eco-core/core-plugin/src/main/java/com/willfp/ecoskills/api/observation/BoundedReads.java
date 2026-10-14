package com.willfp.ecoskills.api.observation;
import com.willfp.eco.core.data.observation.ReadControl;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
/** Dedicated reads; queued time counts toward deadline. A timed-out running read keeps its worker. */
public final class BoundedReads implements AutoCloseable {
 public interface Work<T>{T run(ReadControl control)throws Exception;}
 private final ThreadPoolExecutor workers;
 private final ScheduledThreadPoolExecutor timers;
 private final ConcurrentMap<Object,Entry<?>> pending=new ConcurrentHashMap<>();
 private volatile boolean closed;
 private final long timeoutMillis;
 private static ThreadFactory factory(String name){var sequence=new AtomicInteger();return task->{var thread=new Thread(task,name+sequence.incrementAndGet());thread.setDaemon(true);return thread;};}
 public BoundedReads(){this(2,64,2000);}
 public BoundedReads(int concurrency,int capacity,long timeout){
  timeoutMillis=timeout;
  workers=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),factory("EcoSkills-observation-read-"),new ThreadPoolExecutor.AbortPolicy());
  timers=new ScheduledThreadPoolExecutor(2,factory("EcoSkills-observation-deadline-"));timers.setRemoveOnCancelPolicy(true);
 }
 private final class Entry<T> implements Runnable {
  final Object key;final Work<T> work;final Function<String,T> failure;final CompletableFuture<T> result=new CompletableFuture<>();final AtomicInteger subscribers=new AtomicInteger();
  final ReadControl control=new ReadControl(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
  volatile ScheduledFuture<?> timer;
  Entry(Object key,Work<T> work,Function<String,T> failure){this.key=key;this.work=work;this.failure=failure;}
  public void run(){try{control.check();if(!result.isDone())result.complete(work.run(control));}catch(CancellationException e){result.complete(failure.apply("timeout"));}catch(UnsupportedOperationException e){result.complete(failure.apply("unsupported_backend"));}catch(Exception e){result.complete(failure.apply(closed?"service_stopping":"storage_error"));}finally{if(timer!=null)timer.cancel(false);pending.remove(key,this);}}
  void stop(){result.complete(failure.apply("service_stopping"));}
  void timeout(){result.complete(failure.apply("timeout"));if(workers.remove(this))pending.remove(key,this);control.cancel();}
  CompletableFuture<T> subscribe(){
   subscribers.incrementAndGet();var view=new CompletableFuture<T>();
   result.whenComplete((value,error)->{if(error==null)view.complete(value);else view.completeExceptionally(error);});
   view.whenComplete((value,error)->{if(subscribers.decrementAndGet()==0&&view.isCancelled()&&!result.isDone()){if(timer!=null)timer.cancel(false);result.complete(failure.apply("cancelled"));if(workers.remove(this))pending.remove(key,this);timers.execute(control::cancel);}});return view;
  }
 }
 @SuppressWarnings("unchecked") public <T> CompletableFuture<T> submit(Object key,Work<T> work,Function<String,T> failure){
  if(closed)return CompletableFuture.completedFuture(failure.apply("service_stopping"));
  var created=new Entry<T>(key,work,failure);var existing=pending.putIfAbsent(key,created);
  if(existing!=null)return ((Entry<T>)existing).subscribe();
  var view=created.subscribe();
  try{
   if(closed)throw new RejectedExecutionException();
   created.timer=timers.schedule(created::timeout,timeoutMillis,TimeUnit.MILLISECONDS);workers.execute(created);
  }catch(RejectedExecutionException e){if(created.timer!=null)created.timer.cancel(false);pending.remove(key,created);created.result.complete(failure.apply(closed?"service_stopping":"busy"));}
  return view;
 }
 public int activeReads(){return workers.getActiveCount();}
 public int queuedReads(){return workers.getQueue().size();}
 public void close(){closed=true;for(var entry:pending.values()){if(entry.timer!=null)entry.timer.cancel(false);try{timers.execute(()->{entry.stop();entry.control.cancel();});}catch(RejectedExecutionException ignored){}}workers.shutdownNow();timers.shutdown();pending.clear();}
}
