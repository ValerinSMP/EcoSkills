package com.willfp.ecoskills.api.enchanting;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pre-payment immutable quote. Authentication still requires the registered authority's committed ledger. */
public record EnchantingPurchaseQuote(Context context,String policyVersion,Kind kind,double cumulativeFrom,double cumulativeTo) {
 public static final double MAX_QUOTED_XP=1.0e15;
 public enum Kind { LEGACY_EFFECTIVE_LEVELS, CUMULATIVE_TIERS }
 public record Context(UUID operationId,UUID playerId,String scope,UUID sessionId,String enchantmentKey,
                       int fromTier,int toTier,EnchantingPurchaseEvidence.Mode mode,int vanillaLevelsConsumed) {
  public Context {
   Objects.requireNonNull(operationId);Objects.requireNonNull(playerId);Objects.requireNonNull(sessionId);Objects.requireNonNull(mode);
   if(scope==null||!scope.matches("[A-Za-z0-9._:/-]{1,96}")||enchantmentKey==null||enchantmentKey.length()>128||!enchantmentKey.matches("[a-z0-9._-]+:[a-z0-9/._-]+")||fromTier<0||toTier<=fromTier||toTier>32767)throw new IllegalArgumentException("Quote context");
   boolean custom=mode==EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS||mode==EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS;
   if(vanillaLevelsConsumed<0||(custom?vanillaLevelsConsumed!=0:vanillaLevelsConsumed==0))throw new IllegalArgumentException("Quote consumption");
  }
  public static Context of(EnchantingPurchaseEvidence e){return new Context(e.operationId(),e.playerId(),e.scope(),e.sessionId(),e.enchantmentKey(),e.fromTier(),e.toTier(),e.mode(),e.vanillaLevelsConsumed());}
 }
 public EnchantingPurchaseQuote {
  Objects.requireNonNull(context);Objects.requireNonNull(kind);
  if(policyVersion==null||!policyVersion.matches("[A-Za-z0-9._:/-]{1,96}")||!Double.isFinite(cumulativeFrom)||!Double.isFinite(cumulativeTo)||cumulativeFrom<0||cumulativeTo<cumulativeFrom||cumulativeTo>MAX_QUOTED_XP)throw new IllegalArgumentException("Quote range/version");
  if(cumulativeFrom==0)cumulativeFrom=0;if(cumulativeTo==0)cumulativeTo=0;
  if(kind==Kind.LEGACY_EFFECTIVE_LEVELS && cumulativeFrom!=0)throw new IllegalArgumentException("Legacy quote baseline");
 }
 public double baseXP(){return cumulativeTo-cumulativeFrom;}
 public String payloadHash(){
  String value=String.join("\n",context.operationId().toString(),context.playerId().toString(),context.scope(),context.sessionId().toString(),context.enchantmentKey(),Integer.toString(context.fromTier()),Integer.toString(context.toTier()),context.mode().name(),Integer.toString(context.vanillaLevelsConsumed()),policyVersion,kind.name(),Double.toHexString(cumulativeFrom),Double.toHexString(cumulativeTo));
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
 }
 public static EnchantingPurchaseQuote legacy(Context c){
  double effective=switch(c.mode()){case CUSTOM_SHARDS,CUSTOM_GEMS->6.0*(c.toTier()-c.fromTier());default->c.vanillaLevelsConsumed();};
  return new EnchantingPurchaseQuote(c,EnchantingPurchaseXP.FORMULA_VERSION,Kind.LEGACY_EFFECTIVE_LEVELS,0,3.5*StrictMath.pow(effective,1.5));
 }
}
