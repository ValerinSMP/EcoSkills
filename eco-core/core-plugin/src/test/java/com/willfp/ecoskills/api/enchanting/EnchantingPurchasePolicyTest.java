package com.willfp.ecoskills.api.enchanting;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EnchantingPurchasePolicyTest {
 EnchantingPurchaseQuote.Context context(int from,int to,EnchantingPurchaseEvidence.Mode mode,int spent){return new EnchantingPurchaseQuote.Context(UUID.randomUUID(),UUID.randomUUID(),"survival",UUID.randomUUID(),"minecraft:sharpness",from,to,mode,spent);}
 EnchantingPurchasePolicy policy(){return new EnchantingPurchasePolicy("reviewed-test/1",EnchantingPurchasePolicy.Curve.power(10,1.5),Map.of());}
 @Test void partitionEquivalenceUsesSameCumulativeValuesRegardlessOfCurrencyOrDiscount(){
  var p=policy();var mode=EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS;
  double direct=p.quote(context(0,5,mode,0)).baseXP(),steps=0;
  for(int i=0;i<5;i++)steps+=p.quote(context(i,i+1,mode,0)).baseXP();
  assertEquals(direct,steps,1e-12);
  assertEquals(direct,p.quote(context(0,5,EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,0)).baseXP());
  assertEquals(direct,p.quote(context(0,5,EnchantingPurchaseEvidence.Mode.VANILLA_XP,3)).baseXP());
  assertEquals(direct,p.quote(context(0,5,EnchantingPurchaseEvidence.Mode.VANILLA_XP,30)).baseXP());
 }
 @Test void existingBookTierIsStartingPointNotHistoricalCredit(){
  var quote=policy().quote(context(3,5,EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,0));
  assertEquals(10*StrictMath.pow(5,1.5)-10*StrictMath.pow(3,1.5),quote.baseXP());
 }
 @Test void explicitOverrideIsCompleteMonotonicImmutableAndSupportsZero(){
  var entries=new HashMap<>(Map.of(0,0.0,1,0.0,2,50.0));var curve=EnchantingPurchasePolicy.Curve.tiers(entries);entries.put(2,-1.0);
  var policy=new EnchantingPurchasePolicy("table/1",EnchantingPurchasePolicy.Curve.power(5,1),Map.of("minecraft:sharpness",curve));
  assertEquals(0,policy.quote(context(0,1,EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,0)).baseXP());
  assertEquals(50,policy.quote(context(1,2,EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,0)).baseXP());
  assertThrows(IllegalArgumentException.class,()->policy.quote(context(1,3,EnchantingPurchaseEvidence.Mode.CUSTOM_GEMS,0)));
  assertThrows(IllegalArgumentException.class,()->EnchantingPurchasePolicy.Curve.tiers(Map.of(0,0.0,2,50.0)));
  assertThrows(IllegalArgumentException.class,()->EnchantingPurchasePolicy.Curve.tiers(Map.of(0,0.0,1,50.0,2,40.0)));
 }
 @Test void invalidNumbersAndExcessiveValuesFailBeforePayment(){
  for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->EnchantingPurchasePolicy.Curve.power(bad,1.5));
  assertThrows(IllegalArgumentException.class,()->EnchantingPurchasePolicy.Curve.power(1,0));
  assertThrows(IllegalArgumentException.class,()->EnchantingPurchasePolicy.Curve.power(1e15,2).cumulative(2));
 }
 @Test void legacyQuoteRetainsOriginalFormulaInsteadOfInstallingIllustrativeBalance(){
  var c=context(3,4,EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,0);
  assertEquals(51.43928459844674,EnchantingPurchaseQuote.legacy(c).baseXP(),1e-12);
  assertEquals(EnchantingPurchaseXP.FORMULA_VERSION,EnchantingPurchaseQuote.legacy(c).policyVersion());
 }
}
