package com.willfp.ecoskills.enchanting.persistence;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;
import static org.junit.jupiter.api.Assertions.*;
class JdbcNativeEnchantingGatewayTest {
    JdbcDataSource pool()throws SQLException {
        var p=new JdbcDataSource();p.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var c=p.getConnection();var s=c.createStatement()) {
            s.execute("CREATE TABLE eco_int(profileUUID BINARY(16),dataKey VARCHAR(128),dataValue INT,PRIMARY KEY(profileUUID,dataKey))");
            s.execute("CREATE TABLE eco_double(profileUUID BINARY(16),dataKey VARCHAR(128),dataValue DOUBLE,PRIMARY KEY(profileUUID,dataKey))");
        }return p;
    }
    JdbcNativeEnchantingGateway gateway() {
        return new JdbcNativeEnchantingGateway("eco_","ecoskills:enchanting",0,"ecoskills:enchanting_xp",0,
            Map.of(1,List.of(new JdbcNativeEnchantingGateway.Reward("ecoskills:wisdom",0,2)),2,List.of(new JdbcNativeEnchantingGateway.Reward("ecoskills:wisdom",0,2))));
    }
    int reward(Connection c)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("SELECT dataValue FROM eco_int WHERE dataKey='ecoskills:wisdom'")){return r.next()?r.getInt(1):0;}}
    @Test void updatesExistingNativeScalarTablesAndRewardsWithoutParallelProgress()throws Exception {
        var p=pool();var g=gateway();var id=UUID.randomUUID();
        try(var c=p.getConnection()) {
            c.setAutoCommit(false);var before=g.readForUpdate(c,id);assertEquals(new NativeProgress(0,0),before);
            var plan=DurableEnchantingStore.advance(before,250,50,l->100);g.apply(c,id,plan);c.commit();
            assertEquals(new NativeProgress(2,50),g.readForUpdate(c,id));assertEquals(4,reward(c));
            assertThrows(SQLException.class,()->g.apply(c,id,plan));c.rollback();assertEquals(4,reward(c));
        }
    }
    @Test void rollbackRestoresRewardsAndProgressTogether()throws Exception {
        var p=pool();var g=gateway();var id=UUID.randomUUID();
        try(var c=p.getConnection()) {
            c.setAutoCommit(false);var before=g.readForUpdate(c,id);g.apply(c,id,DurableEnchantingStore.advance(before,250,50,l->100));c.rollback();
            assertEquals(new NativeProgress(0,0),g.readForUpdate(c,id));assertEquals(0,reward(c));
        }
    }
    @Test void deniesForeignNamespacesAndRewardKeyCollision() {
        assertThrows(IllegalArgumentException.class,()->new JdbcNativeEnchantingGateway("eco_","vpets:level",0,"ecoskills:enchanting_xp",0,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new JdbcNativeEnchantingGateway("eco_","ecoskills:enchanting",0,"ecoskills:enchanting_xp",0,Map.of(1,List.of(new JdbcNativeEnchantingGateway.Reward("ecoskills:enchanting",0,1)))));
    }
}
