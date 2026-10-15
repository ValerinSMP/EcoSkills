package com.willfp.ecoskills.enchanting.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;

class DurableEnchantingStoreTest {
    @TempDir Path directory;
    static final class Time extends Clock {
        final AtomicLong now=new AtomicLong(Instant.parse("2026-10-15T12:00:00Z").toEpochMilli());
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now.get());}public long millis(){return now.get();}
    }
    static final class Native implements NativeGateway {
        final AtomicBoolean fail=new AtomicBoolean();
        public NativeProgress readForUpdate(Connection c,UUID player)throws SQLException {
            try(var s=c.prepareStatement("SELECT level_value,xp_value FROM native_profile WHERE player=? FOR UPDATE")) {
                s.setString(1,player.toString());try(var r=s.executeQuery()){return r.next()?new NativeProgress(r.getInt(1),r.getDouble(2)):null;}
            }
        }
        public void apply(Connection c,UUID player,Transition t)throws SQLException {
            try(var s=c.prepareStatement("UPDATE native_profile SET level_value=?,xp_value=?,reward_value=reward_value+? WHERE player=?")) {
                s.setInt(1,t.after().level());s.setDouble(2,t.after().xp());s.setInt(3,2*t.levels().size());s.setString(4,player.toString());assertEquals(1,s.executeUpdate());
            }
            if(fail.get())throw new SQLException("Injected failure after native changes");
        }
    }
    final UUID player=UUID.randomUUID(),session=UUID.randomUUID();final Time time=new Time();final Native gateway=new Native();
    JdbcDataSource pool(String file)throws SQLException {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:file:"+directory.resolve(file)+";MODE=MySQL;LOCK_TIMEOUT=2000");
        try(var c=ds.getConnection();var s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS native_profile(player VARCHAR(36) PRIMARY KEY,level_value INT,xp_value DOUBLE,reward_value INT)");
            try(var q=c.prepareStatement("INSERT INTO native_profile VALUES(?,0,0,0)")){q.setString(1,player.toString());q.executeUpdate();}catch(SQLException e){if(!"23505".equals(e.getSQLState()))throw e;}
        }return ds;
    }
    DurableEnchantingStore store(JdbcDataSource ds)throws SQLException {var s=new DurableEnchantingStore(ds,"test_",time,gateway);s.migrate();return s;}
    Award request(Fence f,double xp) {return request(f,xp,UUID.randomUUID(),"a".repeat(64));}
    Award request(Fence f,double xp,UUID operation,String hash) {
        return new Award(operation,UUID.nameUUIDFromBytes(operation.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),hash,f,xp,new Attribution(UUID.randomUUID(),session,"original-pet-and-session-token"),"confirmed-purchase-evidence-fixture");
    }
    double nativeXP(JdbcDataSource ds)throws SQLException {try(var c=ds.getConnection()){return gateway.readForUpdate(c,player).xp();}}
    @Test void repeatAfterReconstructionAndLostResponseKeepsOneAwardAndProgress()throws Exception {
        var ds=pool("restart");var a=store(ds);var f=a.acquire("survival",player,session,60000);var request=request(f,51.43928459844674);
        var committed=a.award(request,50,l->1000);assertEquals(Status.APPLIED,committed.status());
        var reconstructed=store(pool("restart"));var duplicate=reconstructed.award(request,50,l->1000);
        assertEquals(committed,duplicate);assertEquals(committed,reconstructed.lookup("survival",request.operation()));
        assertEquals(51.43928459844674,nativeXP(ds),1e-12);assertEquals(1,reconstructed.claim("survival",Kind.FINAL_XP,32,1000).size());
    }
    @Test void changedPayloadAndSameIdNeverChangesProgress()throws Exception {
        var ds=pool("conflict");var s=store(ds);var f=s.acquire("survival",player,session,60000);var a=request(f,10);s.award(a,50,l->1000);
        var bad=new Award(a.operation(),a.awardId(),"b".repeat(64),a.fence(),100,a.attribution(),a.confirmedEvidence());
        assertEquals(Status.PAYLOAD_CONFLICT,s.award(bad,50,l->1000).status());assertEquals(10,nativeXP(ds));
    }
    @Test void rollbackIncludesNativeRewardsDailyReceiptAndOutbox()throws Exception {
        var ds=pool("rollback");var s=store(ds);var f=s.acquire("survival",player,session,60000);var a=request(f,250);gateway.fail.set(true);
        assertThrows(SQLException.class,()->s.award(a,50,l->100));assertEquals(0,nativeXP(ds));assertEquals(Status.UNKNOWN,s.lookup("survival",a.operation()).status());
        assertTrue(s.claim("survival",Kind.FINAL_XP,32,1000).isEmpty());gateway.fail.set(false);var committed=s.award(a,50,l->100);
        assertEquals(2,committed.level());assertEquals(50,committed.xp());
        try(var c=ds.getConnection();var st=c.createStatement();var rows=st.executeQuery("SELECT reward_value FROM native_profile")){assertTrue(rows.next());assertEquals(4,rows.getInt(1));}
        s.award(a,50,l->100);
        try(var c=ds.getConnection();var st=c.createStatement();var rows=st.executeQuery("SELECT reward_value FROM native_profile")){assertTrue(rows.next());assertEquals(4,rows.getInt(1));}
        assertEquals(2,s.claim("survival",Kind.LEVEL_EFFECT,32,1000).size());
    }
    @Test void twoStoreInstancesShareCapAndUtcReset()throws Exception {
        var ds=pool("cap");var one=store(ds);var two=store(ds);var f=one.acquire("survival",player,session,60000);
        var first=one.award(request(f,499999.5),1,l->1000000);assertEquals(499999.5,first.finalXP());
        f=two.acquire("survival",player,session,60000);var last=two.award(request(f,100),1,l->1000000);assertEquals(0.5,last.finalXP());
        f=one.acquire("survival",player,session,60000);var zero=one.award(request(f,1),1,l->1000000);assertEquals(0,zero.finalXP());
        time.now.set(Instant.parse("2026-10-16T00:00:00Z").toEpochMilli());f=two.acquire("survival",player,session,60000);
        assertEquals(10,two.award(request(f,10),1,l->1000000).finalXP());
    }
    @Test void delayedWriterAndExpiredSessionCannotUndoCommittedAward()throws Exception {
        var ds=pool("fence");var s=store(ds);var old=s.acquire("survival",player,session,1000);var committed=s.award(request(old,100),50,l->1000);
        assertFalse(s.guardedNativeWrite(old,new NativeProgress(0,0),new NativeProgress(0,5)));assertEquals(100,nativeXP(ds));
        UUID other=UUID.randomUUID();assertNull(s.acquire("survival",player,other,1000));time.now.addAndGet(1001);
        assertNotNull(s.acquire("survival",player,other,1000));assertEquals(Status.PENDING,s.award(request(old,99),50,l->1000).status());assertEquals(100,nativeXP(ds));
    }
    @Test void finalOutboxReplayPreservesAttributionButAmbiguousLegacyEffectsAreNotRepeated()throws Exception {
        var ds=pool("outbox");var s=store(ds);var f=s.acquire("survival",player,session,60000);var a=request(f,250);s.award(a,50,l->100);
        var first=s.claim("survival",Kind.FINAL_XP,1,1000).getFirst();var effects=s.claim("survival",Kind.LEVEL_EFFECT,32,1000);assertEquals(2,effects.size());
        time.now.addAndGet(1001);var recovered=store(pool("outbox"));var again=recovered.claim("survival",Kind.FINAL_XP,1,1000).getFirst();
        assertEquals(first.awardId(),again.awardId());assertEquals(first.attribution(),again.attribution());assertEquals(first.originalSession(),again.originalSession());assertEquals(first.finalXP(),again.finalXP());assertEquals(a.operation(),again.operationId());assertEquals(a.confirmedEvidence(),again.confirmedEvidence());assertEquals(first.receiptPayloadHash(),again.receiptPayloadHash());
        assertNotEquals(first.claim(),again.claim());assertFalse(recovered.acknowledge(first));assertTrue(recovered.acknowledge(again));
        assertTrue(recovered.claim("survival",Kind.FINAL_XP,1,1000).isEmpty());assertTrue(recovered.claim("survival",Kind.LEVEL_EFFECT,32,1000).isEmpty());
        try(var c=ds.getConnection();var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM test_enchanting_outbox WHERE state='REVIEW'")){assertTrue(rows.next());assertEquals(2,rows.getInt(1));}
    }
    @Test void concurrentDuplicateAcrossInstancesHasOneDurableEffect()throws Exception {
        var ds=pool("parallel");var one=store(ds);var two=store(ds);var f=one.acquire("survival",player,session,60000);var a=request(f,25);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var results=workers.invokeAll(List.<Callable<Receipt>>of(()->one.award(a,50,l->1000),()->two.award(a,50,l->1000)));
            for(var result:results)assertEquals(Status.APPLIED,result.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals(25,nativeXP(ds));assertEquals(1,one.claim("survival",Kind.FINAL_XP,32,1000).size());
    }
    @Test void ordinaryWriterChecksNativeBeforeStateInsideTransaction()throws Exception {
        var ds=pool("native");var s=store(ds);var f=s.acquire("survival",player,session,60000);
        assertTrue(s.guardedNativeWrite(f,new NativeProgress(0,0),new NativeProgress(0,25)));
        assertFalse(s.guardedNativeWrite(f,new NativeProgress(0,0),new NativeProgress(0,10)));
        assertEquals(25,nativeXP(ds));
    }

    @Test void attributionChangeForSameOperationIsConflict()throws Exception {
        var ds=pool("pet_conflict");var s=store(ds);var f=s.acquire("survival",player,session,60000);var original=request(f,25);s.award(original,50,l->1000);
        var changed=new Award(original.operation(),original.awardId(),original.payloadHash(),f,25,new Attribution(UUID.randomUUID(),session,"a-different-pet"),original.confirmedEvidence());
        assertEquals(Status.PAYLOAD_CONFLICT,s.award(changed,50,l->1000).status());assertEquals(25,nativeXP(ds));
    }
    @Test void lateWriterCannotPassAfterAnAbaReset()throws Exception {
        var ds=pool("aba");var s=store(ds);var old=s.acquire("survival",player,session,60000);
        assertTrue(s.guardedNativeWrite(old,new NativeProgress(0,0),new NativeProgress(0,25)));
        var current=s.acquire("survival",player,session,60000);
        assertTrue(s.guardedNativeWrite(current,new NativeProgress(0,25),new NativeProgress(0,0)));
        assertFalse(s.guardedNativeWrite(old,new NativeProgress(0,0),new NativeProgress(0,100)));
        assertEquals(0,nativeXP(ds));
    }

    private boolean consumerCommit(JdbcDataSource ds,Delivery delivery)throws SQLException {
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);var save=c.setSavepoint();
            try(var insert=c.prepareStatement("INSERT INTO consumer_receipts(award_id,amount,capture_id) VALUES(?,?,?)")) {
                insert.setString(1,delivery.awardId().toString());insert.setDouble(2,delivery.finalXP());insert.setString(3,delivery.attribution().captureId().toString());insert.executeUpdate();
            }catch(SQLException duplicate) {
                if(!"23505".equals(duplicate.getSQLState()))throw duplicate;c.rollback(save);
                try(var find=c.prepareStatement("SELECT amount,capture_id FROM consumer_receipts WHERE award_id=?")) {
                    find.setString(1,delivery.awardId().toString());try(var row=find.executeQuery()) {
                        if(!row.next()||row.getDouble(1)!=delivery.finalXP()||!row.getString(2).equals(delivery.attribution().captureId().toString()))throw new SQLException("Consumer payload conflict");
                    }
                }c.commit();return false;
            }
            try(var update=c.prepareStatement("UPDATE consumer_progress SET xp=xp+?")){update.setDouble(1,delivery.finalXP());update.executeUpdate();}
            c.commit();return true;
        }
    }
    @Test void durableConsumerSimulationSurvivesCommitWithoutAckAndPreservesOriginalCapture()throws Exception {
        var ds=pool("consumer");var s=store(ds);var f=s.acquire("survival",player,session,60000);var a=request(f,51.43928459844674);s.award(a,50,l->1000);
        try(var c=ds.getConnection();var st=c.createStatement()) {
            st.executeUpdate("CREATE TABLE consumer_receipts(award_id VARCHAR(36) PRIMARY KEY,amount DOUBLE,capture_id VARCHAR(36))");
            st.executeUpdate("CREATE TABLE consumer_progress(xp DOUBLE)");st.executeUpdate("INSERT INTO consumer_progress VALUES(0)");
        }
        var first=s.claim("survival",Kind.FINAL_XP,1,1000).getFirst();assertTrue(consumerCommit(ds,first));
        // Simulate a crash before acknowledge; the durable consumer ledger is reconstructed on a fresh connection.
        time.now.addAndGet(1001);var replay=store(pool("consumer")).claim("survival",Kind.FINAL_XP,1,1000).getFirst();assertFalse(consumerCommit(ds,replay));assertTrue(s.acknowledge(replay));
        try(var c=ds.getConnection();var st=c.createStatement();var row=st.executeQuery("SELECT xp FROM consumer_progress")){assertTrue(row.next());assertEquals(51.43928459844674,row.getDouble(1),1e-12);}
        assertEquals(a.attribution().captureId(),replay.attribution().captureId());
    }

    @Test void outboxDoesNotLeakAcrossScopes()throws Exception {
        var ds=pool("scope");var s=store(ds);var f=s.acquire("survival",player,session,60000);s.award(request(f,25),50,l->1000);
        assertTrue(s.claim("practice",Kind.FINAL_XP,32,1000).isEmpty());
        assertEquals(1,s.claim("survival",Kind.FINAL_XP,32,1000).size());
    }
}
