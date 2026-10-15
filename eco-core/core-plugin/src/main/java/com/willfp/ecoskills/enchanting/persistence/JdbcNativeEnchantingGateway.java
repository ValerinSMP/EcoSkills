package com.willfp.ecoskills.enchanting.persistence;

import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import static com.willfp.ecoskills.enchanting.persistence.DurableEnchantingStore.*;

/** Native eco INT/DOUBLE tables only; never a parallel progress profile.
 * Internal adapter: runtime owner must supply registered EcoSkills keys/defaults and enforce all writer fences.
 */
public final class JdbcNativeEnchantingGateway implements NativeGateway {
    public record Reward(String key,int nativeDefault,int increment) {
        public Reward { JdbcNativeEnchantingGateway.key(key); }
    }
    private final String ints,doubles,levelKey,xpKey;
    private final int startLevel;
    private final double startXP;
    private final Map<Integer,List<Reward>> rewards;
    private final Map<String,Class<? extends Number>> scalarTypes;
    public JdbcNativeEnchantingGateway(String prefix,String levelKey,int nativeStartLevel,String xpKey,double nativeStartXP,Map<Integer,List<Reward>> nativeRewards) {
        if(!prefix.matches("[A-Za-z0-9_]{0,48}"))throw new IllegalArgumentException("Prefix");
        key(levelKey);key(xpKey);if(nativeStartLevel<0||!Double.isFinite(nativeStartXP)||nativeStartXP<0)throw new IllegalArgumentException("Native defaults");
        ints="`"+prefix+"int`";doubles="`"+prefix+"double`";this.levelKey=levelKey;this.xpKey=xpKey;startLevel=nativeStartLevel;startXP=nativeStartXP;
        var copy=new HashMap<Integer,List<Reward>>();
        for(var rows:nativeRewards.values())for(var reward:rows)if(reward.key.equals(levelKey)||reward.key.equals(xpKey))throw new IllegalArgumentException("Reward/progress key collision");
        nativeRewards.forEach((level,rows)->{if(level<1||level>1024)throw new IllegalArgumentException("Reward bound");copy.put(level,List.copyOf(rows));});
        if(copy.size()>1024||copy.values().stream().mapToInt(List::size).sum()>4096)throw new IllegalArgumentException("Reward count");rewards=Map.copyOf(copy);
        var types=new HashMap<String,Class<? extends Number>>();types.put(levelKey,Integer.class);types.put(xpKey,Double.class);
        rewards.values().forEach(rows->rows.forEach(reward->types.put(reward.key,Integer.class)));scalarTypes=Map.copyOf(types);
    }
    private static void key(String name){if(name==null||name.length()>128||!name.matches("ecoskills:[a-z0-9/._-]+"))throw new IllegalArgumentException("Not an EcoSkills native key");}
    private byte[] bytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private PreparedStatement statement(Connection c,String sql,UUID player,String key)throws SQLException {
        var s=c.prepareStatement(sql);s.setQueryTimeout(2);s.setBytes(1,bytes(player));s.setString(2,key);return s;
    }
    private Number read(Connection c,String table,UUID player,String key,Number nativeDefault)throws SQLException {
        try(var s=statement(c,"SELECT dataValue FROM "+table+" WHERE profileUUID=? AND dataKey=? FOR UPDATE",player,key);var r=s.executeQuery()) {
            if(!r.next())return nativeDefault;
            Number result=table.equals(ints)?Integer.valueOf(r.getInt(1)):Double.valueOf(r.getDouble(1));
            if(r.wasNull()||!Double.isFinite(result.doubleValue()))throw new SQLException("Invalid native scalar");return result;
        }
    }
    private void write(Connection c,String table,UUID player,String key,Number value)throws SQLException {
        try(var s=c.prepareStatement("UPDATE "+table+" SET dataValue=? WHERE profileUUID=? AND dataKey=?")) {
            s.setQueryTimeout(2);s.setObject(1,value);s.setBytes(2,bytes(player));s.setString(3,key);
            if(s.executeUpdate()!=0)return;
        }
        try(var s=c.prepareStatement("INSERT INTO "+table+" (profileUUID,dataKey,dataValue) VALUES(?,?,?)")) {
            s.setQueryTimeout(2);s.setBytes(1,bytes(player));s.setString(2,key);s.setObject(3,value);s.executeUpdate();
        }
    }
    @Override public void writeScalar(Connection c,UUID player,String key,Number value)throws SQLException {
        var type=scalarTypes.get(key);
        if(type==null||value==null||!type.isInstance(value)||!Double.isFinite(value.doubleValue()))throw new SQLException("Unregistered or mistyped native scalar");
        write(c,type==Integer.class?ints:doubles,player,key,value);
    }
    @Override public NativeProgress readForUpdate(Connection c,UUID player)throws SQLException {
        return new NativeProgress(read(c,ints,player,levelKey,startLevel).intValue(),read(c,doubles,player,xpKey,startXP).doubleValue());
    }
    @Override public void apply(Connection c,UUID player,Transition transition)throws SQLException {
        // Under the same player fence, this must still be the state used to prepare the projection.
        if(!readForUpdate(c,player).equals(transition.before()))throw new SQLException("Native progress changed");
        var increments=new TreeMap<String,Long>();var defaults=new HashMap<String,Integer>();
        for(int level:transition.levels())for(var reward:rewards.getOrDefault(level,List.of())) {
            if(defaults.putIfAbsent(reward.key,reward.nativeDefault)!=null&&defaults.get(reward.key)!=reward.nativeDefault)throw new SQLException("Inconsistent native default");
            increments.merge(reward.key,(long)reward.increment,Math::addExact);
        }
        for(var entry:increments.entrySet()) {
            long before=read(c,ints,player,entry.getKey(),defaults.get(entry.getKey())).longValue();
            int after=Math.toIntExact(Math.addExact(before,entry.getValue()));write(c,ints,player,entry.getKey(),after);
        }
        write(c,ints,player,levelKey,transition.after().level());write(c,doubles,player,xpKey,transition.after().xp());
    }
}
