package com.willfp.ecoskills.api.observation;
import java.util.*;
public final class ObservationValues {
 private ObservationValues() {}
 public static Map<String,Object> field(Object value,String source) {
  if(value instanceof Number n && !Double.isFinite(n.doubleValue()))return missing("invalid_value");
  return map("value",value,"status","available","source",source);
 }
 public static Map<String,Object> missing(String reason) { return map("value",null,"status","unavailable","reason",reason); }
 public static Map<String,Object> map(Object... pairs) {
  var result=new LinkedHashMap<String,Object>();
  for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);
  return result;
 }
 public static Object freeze(Object value) { return freeze(value,0,new int[]{0,0,256*1024}); }
 private static Object freeze(Object value,int depth,int[] count) {
  if(depth>12||++count[0]>30000)throw new IllegalArgumentException("Observation limit");
  count[1]+=8;if(count[1]>count[2])throw new IllegalArgumentException("Payload limit");
  if(value==null||value instanceof Boolean)return value;
  if(value instanceof String s) { count[1]+=s.length()*6;if(count[1]>count[2])throw new IllegalArgumentException("Payload limit");if(s.length()>256)throw new IllegalArgumentException("Text limit");return s; }
  if(value instanceof Number n) { if(!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Nonfinite");if(!(n instanceof Byte||n instanceof Short||n instanceof Integer||n instanceof Long||n instanceof Float||n instanceof Double))throw new IllegalArgumentException("Mutable number");count[1]+=32;return n; }
  if(value instanceof Map<?,?> map) {
   var out=new LinkedHashMap<String,Object>();
   for(var entry:map.entrySet()) { if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("Key type");count[1]+=key.length()*6+4;if(key.length()>256||count[1]>count[2])throw new IllegalArgumentException("Payload limit");out.put(key,freeze(entry.getValue(),depth+1,count)); }
   return Collections.unmodifiableMap(out);
  }
  if(value instanceof Collection<?> list) {
   if(list.size()>512)throw new IllegalArgumentException("Collection limit");
   var out=new ArrayList<>();for(Object item:list)out.add(freeze(item,depth+1,count));return Collections.unmodifiableList(out);
  }
  throw new IllegalArgumentException("Not a JSON value");
 }
 @SuppressWarnings("unchecked") public static Map<String,Object> immutableLegacy(Map<String,Object> map) { return (Map<String,Object>)freeze(map,0,new int[]{0,0,64*1024}); }
 @SuppressWarnings("unchecked") public static Map<String,Object> immutable(Map<String,Object> map) { return (Map<String,Object>)freeze(map); }
}
