package com.willfp.ecoskills.enchanting;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeStartupTest {
 @Test void waitsForBothReloadsAndStartsExactlyOnceAtFinalReloadEnd() {
  List<Runnable> afterLoad=new ArrayList<>(),afterReload=new ArrayList<>();
  List<String> order=new ArrayList<>();AtomicBoolean categories=new AtomicBoolean(),mysql=new AtomicBoolean();
  RuntimeStartup gate=new RuntimeStartup(afterLoad::add,afterReload::add,()->true,()->{
   assertTrue(categories.get());assertTrue(mysql.get());order.add("initialize");
  },problem->{throw new AssertionError(problem);});
  mysql.set(true);order.add("mysql-initialized");gate.arm();gate.arm();
  assertEquals(1,afterLoad.size());assertEquals(1,afterReload.size());
  categories.set(true);order.add("preliminary-reload");
  afterReload.getFirst().run();assertFalse(order.contains("initialize"));
  afterLoad.getFirst().run();assertFalse(order.contains("initialize"));
  categories.set(false);categories.set(true);order.add("afterLoad-reload-categories-and-handler-completed");
  afterReload.getFirst().run();afterLoad.getFirst().run();afterReload.getFirst().run();gate.arm();
  assertEquals(List.of("mysql-initialized","preliminary-reload","afterLoad-reload-categories-and-handler-completed","initialize"),order);
 }
 @Test void closeSuppressesLateCallbacks() {
  List<Runnable> afterLoad=new ArrayList<>(),afterReload=new ArrayList<>();AtomicInteger calls=new AtomicInteger();
  RuntimeStartup gate=new RuntimeStartup(afterLoad::add,afterReload::add,()->true,calls::incrementAndGet,p->{fail(p);});
  gate.arm();afterLoad.getFirst().run();gate.close();afterReload.getFirst().run();afterLoad.getFirst().run();
  assertEquals(0,calls.get());
 }
 @Test void reportsOriginalFailureWithCauseAndNeverRetries() {
  List<Runnable> afterLoad=new ArrayList<>(),afterReload=new ArrayList<>();List<Throwable> errors=new ArrayList<>();
  var cause=new IllegalArgumentException("invalid scope");var failure=new IllegalStateException("configuration detail",cause);
  RuntimeStartup gate=new RuntimeStartup(afterLoad::add,afterReload::add,()->true,()->{throw failure;},errors::add);
  gate.arm();afterLoad.getFirst().run();afterReload.getFirst().run();afterReload.getFirst().run();
  assertEquals(List.of(failure),errors);assertSame(cause,errors.getFirst().getCause());
 }
 @Test void disabledPluginCannotStart() {
  List<Runnable> afterLoad=new ArrayList<>(),afterReload=new ArrayList<>();
  RuntimeStartup gate=new RuntimeStartup(afterLoad::add,afterReload::add,()->false,()->fail("disabled"),p->{fail(p);});
  gate.arm();afterLoad.getFirst().run();afterReload.getFirst().run();
 }
 @Test void registrationFailureIsReportedOnceWithoutRetry() {
  List<Throwable> errors=new ArrayList<>();var failure=new IllegalStateException("lifecycle unavailable");
  RuntimeStartup gate=new RuntimeStartup(r->{throw failure;},r->fail("registration failed"),()->true,()->fail("failed"),errors::add);
  gate.arm();gate.arm();assertEquals(List.of(failure),errors);
 }
}
