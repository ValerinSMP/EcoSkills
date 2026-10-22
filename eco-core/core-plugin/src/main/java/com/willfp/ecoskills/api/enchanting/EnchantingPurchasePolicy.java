package com.willfp.ecoskills.api.enchanting;
import java.util.*;
/** Owned/configured by vEnchanting, not a second progression curve. No parser, globals, I/O or grant. */
public record EnchantingPurchasePolicy(String version,Curve defaultCurve,Map<String,Curve> overrides) {
 public EnchantingPurchasePolicy {
  if(version==null||!version.matches("[A-Za-z0-9._:/-]{1,96}"))throw new IllegalArgumentException("Policy version");
  Objects.requireNonNull(defaultCurve);overrides=Map.copyOf(overrides);
  if(overrides.size()>512||overrides.entrySet().stream().anyMatch(e->e.getKey().length()>128||!e.getKey().matches("[a-z0-9._-]+:[a-z0-9/._-]+")))throw new IllegalArgumentException("Policy override keys/size");
 }
 public static final class Curve {
  private final double base,power;private final Map<Integer,Double> tiers;
  private Curve(double base,double power,Map<Integer,Double> tiers){this.base=base;this.power=power;this.tiers=tiers;}
  public static Curve power(double base,double power){
   if(!Double.isFinite(base)||base<0||base>EnchantingPurchaseQuote.MAX_QUOTED_XP||!Double.isFinite(power)||power<=0||power>16)throw new IllegalArgumentException("Curve coefficients");
   return new Curve(base,power,null);
  }
  /** Explicit complete 0..maximum cumulative table; 0 must be exactly 0; zero increments are legal. */
  public static Curve tiers(Map<Integer,Double> values){
   Objects.requireNonNull(values);if(values.isEmpty()||values.size()>1025)throw new IllegalArgumentException("Tier table size");
   var sorted=new TreeMap<>(values);double previous=0;
   for(int tier=0;tier<values.size();tier++){
    Double value=sorted.get(tier);
    if(value==null||!Double.isFinite(value)||value<previous||value>EnchantingPurchaseQuote.MAX_QUOTED_XP||(tier==0&&value!=0))throw new IllegalArgumentException("Missing/non-monotonic cumulative tier "+tier);
    previous=value;
   }
   return new Curve(0,0,Collections.unmodifiableMap(sorted));
  }
  public double cumulative(int tier){
   if(tier<0||tier>32767)throw new IllegalArgumentException("Tier");
   Double value; if(tiers==null)value=base*StrictMath.pow(tier,power);else value=tiers.get(tier);
   if(value==null||!Double.isFinite(value)||value<0||value>EnchantingPurchaseQuote.MAX_QUOTED_XP)throw new IllegalArgumentException("Unsupported/out-of-bound tier "+tier);
   return value;
  }
 }
 public EnchantingPurchaseQuote quote(EnchantingPurchaseQuote.Context context){
  Curve curve=overrides.getOrDefault(context.enchantmentKey(),defaultCurve);
  return new EnchantingPurchaseQuote(context,version,EnchantingPurchaseQuote.Kind.CUMULATIVE_TIERS,curve.cumulative(context.fromTier()),curve.cumulative(context.toTier()));
 }
}
