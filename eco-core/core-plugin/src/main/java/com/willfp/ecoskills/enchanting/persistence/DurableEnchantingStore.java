package com.willfp.ecoskills.enchanting.persistence;

import javax.sql.DataSource;
import com.willfp.ecoskills.api.enchanting.EnchantingXPProvider;
import com.willfp.ecoskills.api.enchanting.EnchantingXPConsumer;
import com.willfp.ecoskills.api.enchanting.EnchantingPurchaseEvidence;
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
            // The current native-writer lease may differ after transfer. Purchase attribution never changes.
        }
        String boundPayloadHash() {
            try {
                var hash=java.security.MessageDigest.getInstance("SHA-256");
                for(String field:List.of(payloadHash,operation.toString(),awardId.toString(),fence.scope,fence.player.toString(),attribution.originalSession.toString(),attribution.captureId.toString(),attribution.originalSession.toString(),attribution.opaqueToken,confirmedEvidence)) {
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
        default Map<String,Number> snapshot(Connection transaction,UUID player)throws SQLException {throw new SQLFeatureNotSupportedException("Native publication unavailable");}
        /** Includes native INT/DOUBLE progress and built-in stat/effect reward changes in this same transaction. */
        void apply(Connection transaction, UUID player, Transition transition) throws SQLException;
        /** Optional native scalar writer; implementations must reject unregistered/foreign keys. */
        default void writeScalar(Connection transaction, UUID player, String key, Number value)throws SQLException {
            throw new SQLFeatureNotSupportedException("Native scalar writer not bound");
        }
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
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("settings")+" (singleton INT NOT NULL PRIMARY KEY, scope VARCHAR(96) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("intents")+" (scope VARCHAR(96) NOT NULL, operation_id VARCHAR(36) NOT NULL, player VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, PRIMARY KEY(scope,operation_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("preparations")+" (scope VARCHAR(96) NOT NULL, operation_id VARCHAR(36) NOT NULL, player VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, capture_id VARCHAR(36) NOT NULL, attribution VARCHAR(4096) NOT NULL, phase VARCHAR(24) NOT NULL, evidence_payload VARCHAR(8192), calculation_id VARCHAR(36), lease_until BIGINT NOT NULL, modified_xp DOUBLE, PRIMARY KEY(scope,operation_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("fences")+" (scope VARCHAR(96) NOT NULL, player VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL, lease_until BIGINT NOT NULL, PRIMARY KEY(scope,player))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("writes")+" (scope VARCHAR(96) NOT NULL, player VARCHAR(36) NOT NULL, data_key VARCHAR(128) NOT NULL, revision BIGINT NOT NULL, write_seq BIGINT NOT NULL, PRIMARY KEY(scope,player,data_key))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("daily")+" (scope VARCHAR(96) NOT NULL, player VARCHAR(36) NOT NULL, day_id VARCHAR(10) NOT NULL, spent DOUBLE NOT NULL, PRIMARY KEY(scope,player,day_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("receipts")+" (scope VARCHAR(96) NOT NULL, operation_id VARCHAR(36) NOT NULL, payload_hash VARCHAR(64) NOT NULL, player VARCHAR(36) NOT NULL, award_id VARCHAR(36) NOT NULL, final_xp DOUBLE NOT NULL, level_value INT NOT NULL, xp_value DOUBLE NOT NULL, revision BIGINT NOT NULL, evidence_payload VARCHAR(8192) NOT NULL, session_id VARCHAR(36) NOT NULL, capture_id VARCHAR(36) NOT NULL, attribution VARCHAR(4096) NOT NULL, PRIMARY KEY(scope,operation_id), UNIQUE(award_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS "+table("outbox")+" (award_id VARCHAR(36) NOT NULL, scope VARCHAR(96) NOT NULL, player VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, kind VARCHAR(24) NOT NULL, level_value INT NOT NULL, final_xp DOUBLE NOT NULL, capture_id VARCHAR(36) NOT NULL, attribution VARCHAR(4096) NOT NULL, state VARCHAR(16) NOT NULL, claim_id VARCHAR(36), lease_until BIGINT NOT NULL, operation_id VARCHAR(36) NOT NULL, payload_hash VARCHAR(64) NOT NULL, evidence_payload VARCHAR(8192) NOT NULL, PRIMARY KEY(award_id,kind,level_value))");
            try(var check=c.prepareStatement("SELECT session_id,capture_id,attribution,evidence_payload FROM "+table("receipts")+" WHERE 1=0")){check.setQueryTimeout(2);check.executeQuery().close();}
            String product=c.getMetaData().getDatabaseProductName();
            if(product.equalsIgnoreCase("MySQL")||product.equalsIgnoreCase("MariaDB")) {
                for(String name:List.of(prefix+"int",prefix+"double",prefix+"enchanting_fences",prefix+"enchanting_daily",prefix+"enchanting_receipts",prefix+"enchanting_outbox",prefix+"enchanting_writes",prefix+"enchanting_preparations",prefix+"enchanting_intents",prefix+"enchanting_settings")) {
                    try(var check=c.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                        check.setQueryTimeout(2);check.setString(1,name);try(var row=check.executeQuery()){if(!row.next()||!"InnoDB".equalsIgnoreCase(row.getString(1)))throw new SQLException("Transactional native/durable tables required");}
                    }
                }
            }

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
    /** One physical native profile namespace cannot safely be written under two independent fence scopes. */
    public void bindScope(String scope)throws SQLException {
        if(scope==null||!scope.matches("[A-Za-z0-9._:/-]{1,96}"))throw new IllegalArgumentException("Scope");
        transaction(c->{
            var save=c.setSavepoint();
            try(var s=statement(c,"INSERT INTO "+table("settings")+" (singleton,scope) VALUES(1,?)",scope)){s.executeUpdate();}
            catch(SQLException conflict){if(conflict.getSQLState()==null||!conflict.getSQLState().startsWith("23"))throw conflict;c.rollback(save);}
            try(var s=statement(c,"SELECT scope FROM "+table("settings")+" WHERE singleton=1 FOR UPDATE");var row=s.executeQuery()) {
                if(!row.next()||!scope.equals(row.getString(1)))throw new SQLException("Native profile prefix is already bound to another scope; explicit migration required");
            }return null;
        });
    }
    public record NativeSnapshot(Fence fence,Map<String,Number> values) {public NativeSnapshot{values=Map.copyOf(values);}}
    public NativeSnapshot snapshot(Fence fence)throws SQLException {
        return transaction(c->{if(!lockFence(c,fence))return null;return new NativeSnapshot(fence,nativeState.snapshot(c,fence.player));});
    }
    public NativeSnapshot snapshotCurrent(String scope,UUID player,UUID session)throws SQLException {
        return transaction(c->{
            try(var s=statement(c,"SELECT revision,lease_until FROM "+table("fences")+" WHERE scope=? AND player=? AND session_id=? FOR UPDATE",scope,player,session);var row=s.executeQuery()) {
                if(!row.next()||row.getLong(2)<=now(c))return null;
                var fence=new Fence(scope,player,session,row.getLong(1));return new NativeSnapshot(fence,nativeState.snapshot(c,player));
            }
        });
    }
    public boolean releaseSession(String scope,UUID player,UUID session)throws SQLException {
        return transaction(c->{try(var s=statement(c,"UPDATE "+table("fences")+" SET lease_until=0,revision=revision+1 WHERE scope=? AND player=? AND session_id=?",scope,player,session)){return s.executeUpdate()==1;}});
    }
    public boolean release(Fence fence)throws SQLException {
        return transaction(c->{
            try(var s=statement(c,"UPDATE "+table("fences")+" SET lease_until=0,revision=revision+1 WHERE scope=? AND player=? AND session_id=? AND revision=?",fence.scope,fence.player,fence.session,fence.revision)){return s.executeUpdate()==1;}
        });
    }
    public EnchantingXPConsumer.AttributionRequest intent(String scope,UUID operation)throws SQLException {
        try(var c=pool.getConnection();var s=statement(c,"SELECT player,session_id FROM "+table("intents")+" WHERE scope=? AND operation_id=?",scope,operation);var r=s.executeQuery()) {
            return r.next()?new EnchantingXPConsumer.AttributionRequest(operation,UUID.fromString(r.getString(1)),scope,UUID.fromString(r.getString(2))):null;
        }
    }
    public EnchantingXPConsumer.AttributionRequest retainIntent(EnchantingXPConsumer.AttributionRequest request)throws SQLException {
        return transaction(c->{
            var save=c.setSavepoint();
            try(var s=statement(c,"INSERT INTO "+table("intents")+" (scope,operation_id,player,session_id) VALUES(?,?,?,?)",request.scope(),request.operationId(),request.playerId(),request.originalSession())){s.executeUpdate();}
            catch(SQLException conflict){if(conflict.getSQLState()==null||!conflict.getSQLState().startsWith("23"))throw conflict;c.rollback(save);}
            try(var s=statement(c,"SELECT player,session_id FROM "+table("intents")+" WHERE scope=? AND operation_id=?",request.scope(),request.operationId());var r=s.executeQuery()) {
                if(!r.next()||!r.getString(1).equals(request.playerId().toString()))throw new SQLException("Intent identity conflict");
                return new EnchantingXPConsumer.AttributionRequest(request.operationId(),request.playerId(),request.scope(),UUID.fromString(r.getString(2)));
            }
        });
    }
    public EnchantingXPConsumer.Attribution captured(String scope,UUID operation)throws SQLException {
        try(var c=pool.getConnection()){return captured(c,scope,operation);}
    }
    private EnchantingXPConsumer.Attribution captured(Connection c,String scope,UUID operation)throws SQLException {
        try(var s=statement(c,"SELECT player,session_id,capture_id,attribution FROM "+table("preparations")+" WHERE scope=? AND operation_id=?",scope,operation);var r=s.executeQuery()) {
            if(!r.next())return null;
            return new EnchantingXPConsumer.Attribution(UUID.fromString(r.getString(3)),new EnchantingXPConsumer.AttributionRequest(operation,UUID.fromString(r.getString(1)),scope,UUID.fromString(r.getString(2))),r.getString(4));
        }
    }
    public EnchantingXPConsumer.Attribution retainCapture(EnchantingXPConsumer.Attribution attribution)throws SQLException {
        var a=attribution.request();
        return transaction(c->{
            var previous=captured(c,a.scope(),a.operationId());if(previous!=null){if(!previous.equals(attribution))throw new SQLException("Original capture conflict");return previous;}
            var save=c.setSavepoint();
            try(var s=statement(c,"INSERT INTO "+table("preparations")+" (scope,operation_id,player,session_id,capture_id,attribution,phase,lease_until) VALUES(?,?,?,?,?,?,'CAPTURED',0)",a.scope(),a.operationId(),a.playerId(),a.originalSession(),attribution.captureId(),attribution.opaqueToken())){s.executeUpdate();}
            catch(SQLException duplicate){if(duplicate.getSQLState()==null||!duplicate.getSQLState().startsWith("23"))throw duplicate;c.rollback(save);previous=captured(c,a.scope(),a.operationId());if(!attribution.equals(previous))throw new SQLException("Original capture conflict");}
            return attribution;
        });
    }
    public record Calculation(String phase,UUID token,Double modifiedXP) {}
    public Calculation beginCalculation(EnchantingPurchaseEvidence evidence)throws SQLException {
        return transaction(c->{
            try(var s=statement(c,"SELECT player,session_id,phase,evidence_payload,calculation_id,lease_until,modified_xp FROM "+table("preparations")+" WHERE scope=? AND operation_id=? FOR UPDATE",evidence.scope(),evidence.operationId());var r=s.executeQuery()) {
                if(!r.next())return new Calculation("CAPTURE_ABSENT",null,null);
                if(!r.getString(1).equals(evidence.playerId().toString())||!r.getString(2).equals(evidence.sessionId().toString()))return new Calculation("CONFLICT",null,null);
                String payload=PurchaseEvidenceCodec.encode(evidence),stored=r.getString(4),phase=r.getString(3);
                if(stored!=null&&!stored.equals(payload))return new Calculation("CONFLICT",null,null);
                if(phase.equals("PREPARED"))return new Calculation(phase,null,r.getDouble(7));
                if(phase.equals("CALCULATING")) {
                    if(r.getLong(6)>now(c))return new Calculation("PENDING",null,null);
                    try(var u=statement(c,"UPDATE "+table("preparations")+" SET phase='REVIEW' WHERE scope=? AND operation_id=?",evidence.scope(),evidence.operationId())){u.executeUpdate();}
                    return new Calculation("REVIEW",null,null);
                }
                if(!phase.equals("CAPTURED"))return new Calculation(phase,null,null);
                UUID token=UUID.randomUUID();
                try(var u=statement(c,"UPDATE "+table("preparations")+" SET phase='CALCULATING',evidence_payload=?,calculation_id=?,lease_until=? WHERE scope=? AND operation_id=?",payload,token,now(c)+30000,evidence.scope(),evidence.operationId())){u.executeUpdate();}
                return new Calculation("CALCULATING",token,null);
            }
        });
    }
    public boolean finishCalculation(EnchantingPurchaseEvidence evidence,UUID token,double modified,boolean cancelled)throws SQLException {
        if(!Double.isFinite(modified)||modified<0)throw new IllegalArgumentException("Final XP");
        return transaction(c->{try(var u=statement(c,"UPDATE "+table("preparations")+" SET phase=?,modified_xp=? WHERE scope=? AND operation_id=? AND phase='CALCULATING' AND calculation_id=? AND lease_until>?",cancelled?"REJECTED":"PREPARED",modified,evidence.scope(),evidence.operationId(),token,now(c))){return u.executeUpdate()==1;}});
    }
    public boolean review(Delivery d)throws SQLException {
        return transaction(c->{try(var u=statement(c,"UPDATE "+table("outbox")+" SET state='REVIEW' WHERE award_id=? AND kind=? AND level_value=? AND scope=? AND claim_id=? AND state='RUNNING'",d.awardId,d.kind.name(),d.level,d.scope,d.claim)){return u.executeUpdate()==1;}});
    }
    /** Origin-captured ordinary scalar write. Fence, ordering marker and native value share one transaction.
     * Ordinary per-key flushes do not advance the award revision. An award invalidates ALL old captures.
     * This does not replace the domain mutation queue/quiescence needed around an asynchronous award.
     */
    public enum ScalarWriteResult { WRITTEN, SUPERSEDED, STALE_FENCE }
    public boolean guardedScalarWrite(Fence origin,String key,Number value,long sequence)throws SQLException {
        return writeCapturedScalar(origin,key,value,sequence)==ScalarWriteResult.WRITTEN;
    }
    public ScalarWriteResult writeCapturedScalar(Fence origin,String key,Number value,long sequence)throws SQLException {
        Objects.requireNonNull(origin);Objects.requireNonNull(value);
        if(key==null||key.length()>128||!key.matches("ecoskills:[a-z0-9/._-]+")||sequence<1||
            !(value instanceof Integer||value instanceof Double)||!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException("Native scalar");
        return transaction(c->{
            if(!lockFence(c,origin))return ScalarWriteResult.STALE_FENCE;
            boolean exists=false;
            try(var s=statement(c,"SELECT revision,write_seq FROM "+table("writes")+" WHERE scope=? AND player=? AND data_key=? FOR UPDATE",origin.scope,origin.player,key);var r=s.executeQuery()) {
                if(r.next()){exists=true;if(r.getLong(1)==origin.revision&&r.getLong(2)>=sequence)return ScalarWriteResult.SUPERSEDED;}
            }
            nativeState.writeScalar(c,origin.player,key,value);
            if(exists) {
                try(var u=statement(c,"UPDATE "+table("writes")+" SET revision=?,write_seq=? WHERE scope=? AND player=? AND data_key=?",origin.revision,sequence,origin.scope,origin.player,key)){u.executeUpdate();}
            }else {
                try(var u=statement(c,"INSERT INTO "+table("writes")+" (scope,player,data_key,revision,write_seq) VALUES(?,?,?,?,?)",origin.scope,origin.player,key,origin.revision,sequence)){u.executeUpdate();}
            }
            return ScalarWriteResult.WRITTEN;
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
            try(var s=statement(c,"INSERT INTO "+table("receipts")+" (scope,operation_id,payload_hash,player,award_id,final_xp,level_value,xp_value,revision,evidence_payload,session_id,capture_id,attribution) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",a.fence.scope,a.operation,a.boundPayloadHash(),a.fence.player,a.awardId,amount,transition.after.level,transition.after.xp,revision,a.confirmedEvidence,a.attribution.originalSession,a.attribution.captureId,a.attribution.opaqueToken)){s.executeUpdate();}
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
        try(var s=statement(c,"INSERT INTO "+table("outbox")+" (award_id,scope,player,session_id,kind,level_value,final_xp,capture_id,attribution,state,claim_id,lease_until,operation_id,payload_hash,evidence_payload) VALUES(?,?,?,?,?,?,?,?,?,'READY',NULL,0,?,?,?)",a.awardId,a.fence.scope,a.fence.player,a.attribution.originalSession,kind.name(),level,amount,a.attribution.captureId,a.attribution.opaqueToken,a.operation,a.boundPayloadHash(),a.confirmedEvidence)){s.executeUpdate();}
    }
    /** Poller owns one named consumer per kind. FINAL_XP can replay; ambiguous legacy effects go to REVIEW. */
    public List<Delivery> claim(String scope,Kind kind,int limit,long leaseMillis)throws SQLException {
        return claim(scope,kind,limit,leaseMillis,null);
    }
    public List<Delivery> claimCallbacks(String scope,Set<UUID> localSessions,int limit,long leaseMillis)throws SQLException {
        if(localSessions.size()>512)throw new IllegalArgumentException("Session bound");
        return claim(scope,Kind.LEVEL_EFFECT,limit,leaseMillis,Set.copyOf(localSessions));
    }
    private List<Delivery> claim(String scope,Kind kind,int limit,long leaseMillis,Set<UUID> localSessions)throws SQLException {
        if(limit<1||limit>32||leaseMillis<1000||leaseMillis>60_000)throw new IllegalArgumentException("Outbox bounds");
        return transaction(c->{
            long now=now(c);var result=new ArrayList<Delivery>();
            var parameters=new ArrayList<Object>(List.of(scope,kind.name(),now));String ownership="";
            if(localSessions!=null) {
                ownership=" AND (state='RUNNING' ";
                if(!localSessions.isEmpty()){ownership+=" OR session_id IN ("+String.join(",",Collections.nCopies(localSessions.size(),"?"))+")";parameters.addAll(localSessions);}
                ownership+=" OR NOT EXISTS (SELECT 1 FROM "+table("fences")+" f WHERE f.scope=o.scope AND f.player=o.player AND f.session_id=o.session_id AND f.lease_until>?))";
                parameters.add(now);
            }
            try(var s=statement(c,"SELECT award_id,scope,player,session_id,level_value,final_xp,capture_id,attribution,operation_id,payload_hash,evidence_payload,state FROM "+table("outbox")+" o WHERE scope=? AND kind=? AND (state='READY' OR (state='RUNNING' AND lease_until<=?))"+ownership+" ORDER BY award_id,level_value LIMIT "+limit+" FOR UPDATE",parameters.toArray());var r=s.executeQuery()) {
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
    public int reconcileUncertainCalculations(String scope,int limit)throws SQLException {
        if(limit<1||limit>32)throw new IllegalArgumentException("Reconciliation bound");
        return transaction(c->{int count=0;long time=now(c);
            try(var s=statement(c,"SELECT operation_id FROM "+table("preparations")+" WHERE scope=? AND phase='CALCULATING' AND lease_until<=? ORDER BY operation_id LIMIT "+limit+" FOR UPDATE",scope,time);var rows=s.executeQuery()) {
                while(rows.next())try(var u=statement(c,"UPDATE "+table("preparations")+" SET phase='REVIEW' WHERE scope=? AND operation_id=? AND phase='CALCULATING' AND lease_until<=?",scope,rows.getString(1),time)){count+=u.executeUpdate();}
            }return count;
        });
    }
    /** Explicit bounded reconciliation; never claims READY callbacks or executes external commands.
     * A callback may have executed before its ack was lost. Keep REVIEW for human/domain reconciliation.
     */
    public int reconcileUncertainLevelCallbacks(String scope,int limit)throws SQLException {
        if(scope==null||!scope.matches("[A-Za-z0-9._:/-]{1,96}")||limit<1||limit>32)throw new IllegalArgumentException("Reconciliation bounds");
        return transaction(c->{
            long time=now(c);int changed=0;
            try(var s=statement(c,"SELECT award_id,level_value,claim_id FROM "+table("outbox")+" WHERE scope=? AND kind='LEVEL_EFFECT' AND state='RUNNING' AND lease_until<=? ORDER BY award_id,level_value LIMIT "+limit+" FOR UPDATE",scope,time);var r=s.executeQuery()) {
                while(r.next())try(var u=statement(c,"UPDATE "+table("outbox")+" SET state='REVIEW' WHERE scope=? AND award_id=? AND kind='LEVEL_EFFECT' AND level_value=? AND state='RUNNING' AND claim_id=? AND lease_until<=?",scope,r.getString(1),r.getInt(2),r.getString(3),time)){changed+=u.executeUpdate();}
            }
            return changed;
        });
    }
    /** Internal callback acknowledgement: compares the entire claimed immutable body, not only its token. */
    public boolean acknowledge(Delivery delivery)throws SQLException {
        return transaction(c->{
            try(var s=statement(c,"SELECT scope,player,session_id,final_xp,capture_id,attribution,operation_id,payload_hash,evidence_payload,lease_until FROM "+table("outbox")+" WHERE award_id=? AND kind=? AND level_value=? AND claim_id=? AND state='RUNNING' FOR UPDATE",delivery.awardId,delivery.kind.name(),delivery.level,delivery.claim);var r=s.executeQuery()) {
                if(!r.next()||r.getLong(10)<=now(c)||!r.getString(1).equals(delivery.scope)||!r.getString(2).equals(delivery.player.toString())||
                    !r.getString(3).equals(delivery.originalSession.toString())||Double.doubleToLongBits(r.getDouble(4))!=Double.doubleToLongBits(delivery.finalXP)||
                    !r.getString(5).equals(delivery.attribution.captureId.toString())||!r.getString(6).equals(delivery.attribution.opaqueToken)||
                    !delivery.attribution.originalSession.equals(delivery.originalSession)||!r.getString(7).equals(delivery.operationId.toString())||
                    !r.getString(8).equals(delivery.receiptPayloadHash)||!r.getString(9).equals(delivery.confirmedEvidence))return false;
            }
            try(var u=statement(c,"UPDATE "+table("outbox")+" SET state='ACKED' WHERE award_id=? AND kind=? AND level_value=? AND claim_id=? AND state='RUNNING'",delivery.awardId,delivery.kind.name(),delivery.level,delivery.claim)){return u.executeUpdate()==1;}
        });
    }

    /** Committed receipt reconstruction; does not echo request payload as evidence. */
    public EnchantingXPProvider.Observation lookupReceipt(EnchantingXPProvider.ReceiptKey key)throws SQLException {
        return transaction(c->observe(c,key));
    }
    private EnchantingXPProvider.Observation observe(Connection c,EnchantingXPProvider.ReceiptKey key)throws SQLException {
        try(var s=statement(c,"SELECT award_id,final_xp,level_value,xp_value,revision,evidence_payload,session_id,capture_id,attribution,payload_hash,player FROM "+table("receipts")+" WHERE scope=? AND operation_id=?",key.scope(),key.operationId());var r=s.executeQuery()) {
            if(!r.next())return observation(EnchantingXPProvider.State.NOT_FOUND,"receipt_not_found",null);
            if(!r.getString(1).equals(key.awardId().toString()))return observation(EnchantingXPProvider.State.PAYLOAD_CONFLICT,"award_identity",null);
            try {
                var evidence=PurchaseEvidenceCodec.decode(r.getString(6));
                if(!evidence.operationId().equals(key.operationId())||!evidence.scope().equals(key.scope())||
                    !evidence.playerId().toString().equals(r.getString(11))||!evidence.sessionId().toString().equals(r.getString(7)))
                    return observation(EnchantingXPProvider.State.REJECTED,"receipt_evidence_corrupt",null);
                var attribution=new EnchantingXPConsumer.Attribution(UUID.fromString(r.getString(8)),new EnchantingXPConsumer.AttributionRequest(key.operationId(),evidence.playerId(),key.scope(),UUID.fromString(r.getString(7))),r.getString(9));
                var reconstructed=new Award(key.operationId(),key.awardId(),evidence.payloadHash(),new Fence(key.scope(),evidence.playerId(),evidence.sessionId(),r.getLong(5)),0,
                    new Attribution(attribution.captureId(),evidence.sessionId(),attribution.opaqueToken()),r.getString(6));
                if(!reconstructed.boundPayloadHash().equals(r.getString(10)))return observation(EnchantingXPProvider.State.REJECTED,"receipt_binding_corrupt",null);
                var receipt=new EnchantingXPProvider.CommittedReceipt(evidence,key.awardId(),r.getDouble(2),attribution,r.getString(10),r.getInt(3),r.getDouble(4),r.getLong(5));
                return observation(EnchantingXPProvider.State.APPLIED,null,receipt);
            }catch(java.io.IOException|IllegalArgumentException corrupt){return observation(EnchantingXPProvider.State.REJECTED,"receipt_corrupt",null);}
        }
    }
    private static EnchantingXPProvider.Observation observation(EnchantingXPProvider.State state,String reason,EnchantingXPProvider.CommittedReceipt receipt) {
        return new EnchantingXPProvider.Observation(state,reason,receipt);
    }
    private static boolean samePayload(EnchantingXPProvider.CommittedReceipt receipt,EnchantingXPConsumer.Delivery delivery) {
        return receipt.evidence().equals(delivery.evidence())&&receipt.awardId().equals(delivery.awardId())&&
            Double.doubleToLongBits(receipt.finalXP())==Double.doubleToLongBits(delivery.finalXP())&&
            receipt.originalAttribution().equals(delivery.originalAttribution())&&receipt.receiptPayloadHash().equals(delivery.receiptPayloadHash());
    }
    public EnchantingXPProvider.Observation verifyDelivery(EnchantingXPConsumer.Delivery delivery)throws SQLException {
        return transaction(c->{
            var observed=observe(c,new EnchantingXPProvider.ReceiptKey(delivery.evidence().scope(),delivery.evidence().operationId(),delivery.awardId()));
            if(observed.state()!=EnchantingXPProvider.State.APPLIED)return observed;
            if(!samePayload(observed.receipt(),delivery))return observation(EnchantingXPProvider.State.PAYLOAD_CONFLICT,"delivery_payload",null);
            try(var s=statement(c,"SELECT state,claim_id,lease_until FROM "+table("outbox")+" WHERE award_id=? AND scope=? AND kind='FINAL_XP' AND level_value=0 FOR UPDATE",delivery.awardId(),delivery.evidence().scope());var r=s.executeQuery()) {
                if(!r.next())return observation(EnchantingXPProvider.State.REJECTED,"delivery_absent",null);
                if(!"RUNNING".equals(r.getString(1))||!delivery.deliveryAttempt().toString().equals(r.getString(2))||r.getLong(3)<=now(c))
                    return observation(EnchantingXPProvider.State.REJECTED,"attempt_not_current",null);
            }
            return observed;
        });
    }
    /** Trust only the authorized consumer's committed outcome. Validation and CAS are one transaction. */
    public EnchantingXPProvider.Ack acknowledgeDelivery(EnchantingXPConsumer.Delivery delivery,EnchantingXPConsumer.Consumption result)throws SQLException {
        if(result!=EnchantingXPConsumer.Consumption.APPLIED&&result!=EnchantingXPConsumer.Consumption.ALREADY_APPLIED)return EnchantingXPProvider.Ack.REJECTED;
        return transaction(c->{
            var receipt=observe(c,new EnchantingXPProvider.ReceiptKey(delivery.evidence().scope(),delivery.evidence().operationId(),delivery.awardId()));
            if(receipt.state()!=EnchantingXPProvider.State.APPLIED)return receipt.state()==EnchantingXPProvider.State.NOT_FOUND?EnchantingXPProvider.Ack.NOT_COMMITTED:
                receipt.state()==EnchantingXPProvider.State.PAYLOAD_CONFLICT?EnchantingXPProvider.Ack.PAYLOAD_CONFLICT:EnchantingXPProvider.Ack.REJECTED;
            if(!samePayload(receipt.receipt(),delivery))return EnchantingXPProvider.Ack.PAYLOAD_CONFLICT;
            try(var s=statement(c,"SELECT state,claim_id,lease_until FROM "+table("outbox")+" WHERE award_id=? AND scope=? AND kind='FINAL_XP' AND level_value=0 FOR UPDATE",delivery.awardId(),delivery.evidence().scope());var r=s.executeQuery()) {
                if(!r.next())return EnchantingXPProvider.Ack.NOT_COMMITTED;
                if(!delivery.deliveryAttempt().toString().equals(r.getString(2)))return EnchantingXPProvider.Ack.STALE_ATTEMPT;
                if("ACKED".equals(r.getString(1)))return EnchantingXPProvider.Ack.ALREADY_ACKED;
                if(!"RUNNING".equals(r.getString(1))||r.getLong(3)<=now(c))return EnchantingXPProvider.Ack.STALE_ATTEMPT;
            }
            try(var u=statement(c,"UPDATE "+table("outbox")+" SET state='ACKED' WHERE award_id=? AND scope=? AND kind='FINAL_XP' AND level_value=0 AND state='RUNNING' AND claim_id=?",delivery.awardId(),delivery.evidence().scope(),delivery.deliveryAttempt())) {
                return u.executeUpdate()==1?EnchantingXPProvider.Ack.ACKED:EnchantingXPProvider.Ack.STALE_ATTEMPT;
            }
        });
    }
}
