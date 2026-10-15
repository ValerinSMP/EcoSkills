package com.willfp.ecoskills.enchanting.persistence;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;

/** Internal transactional kernel. All methods perform I/O and MUST run off the Bukkit main thread.
 * No runtime capability is enabled merely by constructing this class.
 * NativeGateway MUST use this connection and enforce the same fence for EVERY ordinary writer.
 */
public final class DurableEnchantingStore {
    public static final double DAILY_LIMIT=500_000.0;
    private final DataSource pool;
    private final String prefix;
    private final Clock clock;
    private final NativeGateway nativeState;
    private final ThreadLocal<Long> transactionDeadline=new ThreadLocal<>();
    public enum Status { APPLIED, DECLINED, PENDING, UNKNOWN, PAYLOAD_CONFLICT }
    public enum Kind { FINAL_XP, LEVEL_EFFECT }
    public record Fence(String scope, UUID player, UUID session, long revision) {}
    public record Receipt(Status status, String reason, UUID awardId, double finalXP, int level, double xp, long revision) {}
    public record Attribution(UUID captureId, UUID originalSession, String opaqueToken) {
        public Attribution {
            Objects.requireNonNull(captureId);Objects.requireNonNull(originalSession);Objects.requireNonNull(opaqueToken);
            if(opaqueToken.length()>4096)throw new IllegalArgumentException("Attribution token limit");
        }
    }
    public record Award(UUID operation, UUID awardId, String payloadHash, Fence fence,
                        double modifiedFinalXP, Attribution attribution, String confirmedEvidence) {
        public Award {
            Objects.requireNonNull(operation);Objects.requireNonNull(awardId);Objects.requireNonNull(fence);Objects.requireNonNull(attribution);
            Objects.requireNonNull(payloadHash);Objects.requireNonNull(confirmedEvidence);
            if(confirmedEvidence.isBlank()||confirmedEvidence.length()>8192)throw new IllegalArgumentException("Confirmed evidence bound");
            if(!payloadHash.matches("[0-9a-f]{64}")||!Double.isFinite(modifiedFinalXP)||modifiedFinalXP<0)
                throw new IllegalArgumentException("Invalid prepared award");
            if(!fence.session.equals(attribution.originalSession))throw new IllegalArgumentException("Attribution session mismatch");
        }
        String boundPayloadHash() {
            try {
                var hash=java.security.MessageDigest.getInstance("SHA-256");
                for(String field:List.of(payloadHash,operation.toString(),awardId.toString(),fence.scope,fence.player.toString(),fence.session.toString(),attribution.captureId.toString(),attribution.originalSession.toString(),attribution.opaqueToken,confirmedEvidence)) {
                    byte[] bytes=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
                }
                return java.util.HexFormat.of().formatHex(hash.digest());
            }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    public record NativeProgress(int level,double xp) {
        public NativeProgress { if(level<0||!Double.isFinite(xp)||xp<0)throw new IllegalArgumentException("Native progress"); }
    }
    /** Projection contains only native-owned scalar changes and crossed levels; no commands/callbacks in SQL. */
    public record Transition(NativeProgress before, NativeProgress after, List<Integer> levels) {
        public Transition { levels=List.copyOf(levels); }
    }
    public interface NativeGateway {
        NativeProgress readForUpdate(Connection transaction, UUID player) throws SQLException;
        /** Includes native INT/DOUBLE progress and built-in stat/effect reward changes in this same transaction. */
        void apply(Connection transaction, UUID player, Transition transition) throws SQLException;
    }
    public record Delivery(UUID awardId, String scope, UUID player, UUID originalSession, Kind kind,
                           int level, double finalXP, Attribution attribution, UUID claim, UUID operationId, String receiptPayloadHash, String confirmedEvidence) {}
    public DurableEnchantingStore(DataSource pool,String prefix,NativeGateway gateway) { this(pool,prefix,null,gateway); }
    // Package-private deterministic clock exists only for isolated tests. Production uses the database clock.
    DurableEnchantingStore(DataSource pool,String prefix,Clock clock,NativeGateway gateway) {
        this.pool=Objects.requireNonNull(pool);this.clock=clock;nativeState=Objects.requireNonNull(gateway);
        if(!prefix.matches("[A-Za-z0-9_]{0,48}"))throw new IllegalArgumentException("Unsafe prefix");this.prefix=prefix;
    }
    private long now(Connection c)throws SQLException {
        if(clock!=null)return clock.millis();
        try(var s=statement(c,"SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)");var r=s.executeQuery()) {
            if(!r.next())throw new SQLException("Database clock unavailable");return r.getLong(1);
        }
    }
    private String table(String name){return "`"+prefix+"enchanting_"+name+"`";}
    private void deadline()throws SQLTimeoutException {
        Long end=transactionDeadline.get();if(end!=null&&System.nanoTime()-end>=0)throw new SQLTimeoutException("Award transaction deadline");
    }
    private PreparedStatement statement(Connection c,String sql,Object...values)throws SQLException {
        deadline();var s=c.prepareStatement(sql);s.setQueryTimeout(2);
        for(int i=0;i<values.length;i++){Object v=values[i];s.setObject(i+1,v instanceof UUID?v.toString():v);}
        return s;
    }
    /** Explicit migration, never called by a constructor or read. Not executed on production by this increment. */
    public void migrate()throws SQLException {
        try(var c=pool.getConnection();var s=c.createStatement()) {
            s.setQueryTimeout(2);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("fences")+" (scope VARCHAR(96) NOT NULL, player VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL, lease_until BIGINT NOT NULL, PRIMARY KEY(scope,player))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("daily")+" (scope VARCHAR(96) NOT NULL, player VARCHAR(36) NOT NULL, day_id VARCHAR(10) NOT NULL, spent DOUBLE NOT NULL, PRIMARY KEY(scope,player,day_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("receipts")+" (scope VARCHAR(96) NOT NULL, operation_id VARCHAR(36) NOT NULL, payload_hash VARCHAR(64) NOT NULL, player VARCHAR(36) NOT NULL, award_id VARCHAR(36) NOT NULL, final_xp DOUBLE NOT NULL, level_value INT NOT NULL, xp_value DOUBLE NOT NULL, revision BIGINT NOT NULL, evidence_payload VARCHAR(8192) NOT NULL, PRIMARY KEY(scope,operation_id), UNIQUE(award_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("outbox")+" (award_id VARCHAR(36) NOT NULL, scope VARCHAR(96) NOT NULL, player VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, kind VARCHAR(24) NOT NULL, level_value INT NOT NULL, final_xp DOUBLE NOT NULL, capture_id VARCHAR(36) NOT NULL, attribution VARCHAR(4096) NOT NULL, state VARCHAR(16) NOT NULL, claim_id VARCHAR(36), lease_until BIGINT NOT NULL, operation_id VARCHAR(36) NOT NULL, payload_hash VARCHAR(64) NOT NULL, evidence_payload VARCHAR(8192) NOT NULL, PRIMARY KEY(award_id,kind,level_value))");
        }
    }
    private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T transaction(Work<T> work)throws SQLException {
        if(transactionDeadline.get()!=null)throw new IllegalStateException("Nested award transaction");
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        try(var c=pool.getConnection()) {
            transactionDeadline.set(end);deadline();
            boolean auto=c.getAutoCommit();int isolation=c.getTransactionIsolation();
            try {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
                T answer=work.run(c);deadline();c.commit();return answer;
            } catch(SQLException|RuntimeException|Error e) {try{c.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);try{c.close();}catch(SQLException close){e.addSuppressed(close);}}throw e;}
            finally {if(!c.isClosed()){c.setAutoCommit(auto);c.setTransactionIsolation(isolation);}}
        } finally {transactionDeadline.remove();}
    }
    /** Caller establishes a genuine node/session identity. A live lease cannot be stolen. */
    public Fence acquire(String scope,UUID player,UUID session,long leaseMillis)throws SQLException {
        if(!scope.matches("[A-Za-z0-9._:/-]{1,96}")||leaseMillis<1000||leaseMillis>60_000)throw new IllegalArgumentException("Lease");
        return transaction(c->{
            long now=now(c);
            // Savepoint allows a concurrent first insert to lose the race without aborting this transaction.
            var save=c.setSavepoint();
            try(var s=statement(c,"INSERT INTO "+table("fences")+" (scope,player,session_id,revision,lease_until) VALUES(?,?,?,0,0)",scope,player,session)){s.executeUpdate();}
            catch(SQLException conflict){if(conflict.getSQLState()==null||!conflict.getSQLState().startsWith("23"))throw conflict;c.rollback(save);}
            try(var s=statement(c,"SELECT session_id,revision,lease_until FROM "+table("fences")+" WHERE scope=? AND player=? FOR UPDATE",scope,player);var row=s.executeQuery()) {
                if(!row.next())throw new SQLException("Fence missing");long revision=row.getLong(2);
                if(row.getLong(3)>now&&!row.getString(1).equals(session.toString()))return null;
                if(!row.getString(1).equals(session.toString()))revision=Math.addExact(revision,1);
                try(var u=statement(c,"UPDATE "+table("fences")+" SET session_id=?,revision=?,lease_until=? WHERE scope=? AND player=?",session,revision,Math.addExact(now,leaseMillis),scope,player)){u.executeUpdate();}
                return new Fence(scope,player,session,revision);
            }
        });
    }
    private boolean lockFence(Connection c,Fence f)throws SQLException {
        try(var s=statement(c,"SELECT session_id,revision,lease_until FROM "+table("fences")+" WHERE scope=? AND player=? FOR UPDATE",f.scope,f.player);var r=s.executeQuery()) {
            return r.next()&&r.getString(1).equals(f.session.toString())&&r.getLong(2)==f.revision&&r.getLong(3)>now(c);
        }
    }
    /** Ordinary native writer uses this SAME transaction boundary and its captured session/revision. */
    public boolean guardedNativeWrite(Fence captured, NativeProgress before,NativeProgress after)throws SQLException {
        return transaction(c->{
            if(!lockFence(c,captured))return false;
            if(!nativeState.readForUpdate(c,captured.player).equals(before))return false;
            nativeState.apply(c,captured.player,new Transition(before,after,List.of()));
            try(var u=statement(c,"UPDATE "+table("fences")+" SET revision=? WHERE scope=? AND player=?",Math.addExact(captured.revision,1),captured.scope,captured.player)){u.executeUpdate();}
            return true;
        });
    }
    public Receipt lookup(String scope,UUID operation)throws SQLException {
        try(var c=pool.getConnection()){return receipt(c,scope,operation,null);}
    }
    private Receipt receipt(Connection c,String scope,UUID operation,String payload)throws SQLException {
        try(var s=statement(c,"SELECT payload_hash,award_id,final_xp,level_value,xp_value,revision FROM "+table("receipts")+" WHERE scope=? AND operation_id=?",scope,operation);var r=s.executeQuery()) {
            if(!r.next())return new Receipt(Status.UNKNOWN,"not_found",null,0,0,0,0);
            if(payload!=null&&!r.getString(1).equals(payload))return new Receipt(Status.PAYLOAD_CONFLICT,"payload_conflict",null,0,0,0,0);
            return new Receipt(Status.APPLIED,null,UUID.fromString(r.getString(2)),r.getDouble(3),r.getInt(4),r.getDouble(5),r.getLong(6));
        }
    }
    public Receipt award(Award a,int maxLevel,DoubleUnaryOperator requirement)throws SQLException {
        return transaction(c->{
            // All operations for a player serialize on its fence. Initial lookup gives repeatability after transfer.
            Receipt previous=receipt(c,a.fence.scope,a.operation,a.boundPayloadHash());
            if(previous.status!=Status.UNKNOWN)return previous;
            boolean validFence=lockFence(c,a.fence);
            // A concurrent identical operation may have committed while this call waited on the fence.
            previous=receipt(c,a.fence.scope,a.operation,a.boundPayloadHash());
            if(previous.status!=Status.UNKNOWN)return previous;
            if(!validFence)return new Receipt(Status.PENDING,"stale_or_absent_fence",a.awardId,0,0,0,0);
            NativeProgress before=nativeState.readForUpdate(c,a.fence.player);
            if(before==null)return new Receipt(Status.PENDING,"native_progress_absent",a.awardId,0,0,0,a.fence.revision);
            String day=Instant.ofEpochMilli(now(c)).atZone(ZoneOffset.UTC).toLocalDate().toString();double spent=0;
            try(var s=statement(c,"SELECT spent FROM "+table("daily")+" WHERE scope=? AND player=? AND day_id=? FOR UPDATE",a.fence.scope,a.fence.player,day);var r=s.executeQuery()) {if(r.next())spent=r.getDouble(1);else spent=-1;}
            if(spent<0){try(var s=statement(c,"INSERT INTO "+table("daily")+" (scope,player,day_id,spent) VALUES(?,?,?,0)",a.fence.scope,a.fence.player,day)){s.executeUpdate();}spent=0;}
            if(!Double.isFinite(spent)||spent<0)throw new SQLException("Invalid daily usage");
            double amount=Math.min(a.modifiedFinalXP,Math.max(0,DAILY_LIMIT-spent));
            Transition transition=amount==0?new Transition(before,before,List.of()):advance(before,amount,maxLevel,requirement);
            nativeState.apply(c,a.fence.player,transition);
            long revision=Math.addExact(a.fence.revision,1);
            try(var s=statement(c,"UPDATE "+table("fences")+" SET revision=? WHERE scope=? AND player=?",revision,a.fence.scope,a.fence.player)){s.executeUpdate();}
            try(var s=statement(c,"UPDATE "+table("daily")+" SET spent=? WHERE scope=? AND player=? AND day_id=?",spent+amount,a.fence.scope,a.fence.player,day)){s.executeUpdate();}
            try(var s=statement(c,"INSERT INTO "+table("receipts")+" (scope,operation_id,payload_hash,player,award_id,final_xp,level_value,xp_value,revision,evidence_payload) VALUES(?,?,?,?,?,?,?,?,?,?)",a.fence.scope,a.operation,a.boundPayloadHash(),a.fence.player,a.awardId,amount,transition.after.level,transition.after.xp,revision,a.confirmedEvidence)){s.executeUpdate();}
            // Zero final XP is a durable cap-exhausted result, never a pet notification.
            if(amount>0)insertOutbox(c,a,Kind.FINAL_XP,0,amount);
            for(int level:transition.levels)insertOutbox(c,a,Kind.LEVEL_EFFECT,level,amount);
            return new Receipt(Status.APPLIED,null,a.awardId,amount,transition.after.level,transition.after.xp,revision);
        });
    }
    public static Transition advance(NativeProgress before,double amount,int maxLevel,DoubleUnaryOperator required) {
        if(!Double.isFinite(amount)||amount<0||maxLevel<0)throw new IllegalArgumentException("Progression");
        int level=before.level;double xp=before.xp+amount;var crossed=new ArrayList<Integer>();
        if(!Double.isFinite(xp))throw new IllegalArgumentException("XP overflow");
        while(level<maxLevel) {
            double cost=required.applyAsDouble(level);
            if(!Double.isFinite(cost)||cost<=0)throw new IllegalArgumentException("Invalid native requirement");
            if(xp<cost)break;if(crossed.size()>=1024)throw new IllegalArgumentException("Level-up bound");
            xp-=cost;crossed.add(++level);
        }
        return new Transition(before,new NativeProgress(level,xp),crossed);
    }
    private void insertOutbox(Connection c,Award a,Kind kind,int level,double amount)throws SQLException {
        try(var s=statement(c,"INSERT INTO "+table("outbox")+" (award_id,scope,player,session_id,kind,level_value,final_xp,capture_id,attribution,state,claim_id,lease_until,operation_id,payload_hash,evidence_payload) VALUES(?,?,?,?,?,?,?,?,?,'READY',NULL,0,?,?,?)",a.awardId,a.fence.scope,a.fence.player,a.fence.session,kind.name(),level,amount,a.attribution.captureId,a.attribution.opaqueToken,a.operation,a.boundPayloadHash(),a.confirmedEvidence)){s.executeUpdate();}
    }
    /** Poller owns one named consumer per kind. FINAL_XP can replay; ambiguous legacy effects go to REVIEW. */
    public List<Delivery> claim(String scope,Kind kind,int limit,long leaseMillis)throws SQLException {
        if(limit<1||limit>32||leaseMillis<1000||leaseMillis>60_000)throw new IllegalArgumentException("Outbox bounds");
        return transaction(c->{
            long now=now(c);var result=new ArrayList<Delivery>();
            try(var s=statement(c,"SELECT award_id,scope,player,session_id,level_value,final_xp,capture_id,attribution,operation_id,payload_hash,evidence_payload,state FROM "+table("outbox")+" WHERE scope=? AND kind=? AND (state='READY' OR (state='RUNNING' AND lease_until<=?)) ORDER BY award_id,level_value LIMIT "+limit+" FOR UPDATE",scope,kind.name(),now);var r=s.executeQuery()) {
                while(r.next()) {
                    UUID award=UUID.fromString(r.getString(1)),player=UUID.fromString(r.getString(3)),session=UUID.fromString(r.getString(4)),claim=UUID.randomUUID();int level=r.getInt(5);
                    if(kind==Kind.LEVEL_EFFECT&&r.getString(12).equals("RUNNING")) {
                        try(var review=statement(c,"UPDATE "+table("outbox")+" SET state=? WHERE award_id=? AND kind=? AND level_value=?","REVIEW",award,kind.name(),level)){review.executeUpdate();}
                        continue;
                    }
                    try(var u=statement(c,"UPDATE "+table("outbox")+" SET state='RUNNING',claim_id=?,lease_until=? WHERE award_id=? AND kind=? AND level_value=?",claim,Math.addExact(now,leaseMillis),award,kind.name(),level)){u.executeUpdate();}
                    result.add(new Delivery(award,r.getString(2),player,session,kind,level,r.getDouble(6),new Attribution(UUID.fromString(r.getString(7)),session,r.getString(8)),claim,UUID.fromString(r.getString(9)),r.getString(10),r.getString(11)));
                }
            }
            return List.copyOf(result);
        });
    }
    public boolean acknowledge(Delivery delivery)throws SQLException {
        try(var c=pool.getConnection();var s=statement(c,"UPDATE "+table("outbox")+" SET state='ACKED' WHERE award_id=? AND kind=? AND level_value=? AND claim_id=? AND scope=? AND player=? AND state='RUNNING'",delivery.awardId,delivery.kind.name(),delivery.level,delivery.claim,delivery.scope,delivery.player)){return s.executeUpdate()==1;}
    }
}
