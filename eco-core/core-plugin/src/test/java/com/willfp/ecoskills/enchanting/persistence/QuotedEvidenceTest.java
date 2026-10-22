package com.willfp.ecoskills.enchanting.persistence;
import com.willfp.ecoskills.api.enchanting.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QuotedEvidenceTest {
 EnchantingPurchaseEvidence evidence(){return new EnchantingPurchaseEvidence(UUID.randomUUID(),UUID.randomUUID(),"survival",UUID.randomUUID(),"minecraft:sharpness",3,5,EnchantingPurchaseEvidence.Mode.VANILLA_XP,15,"prices/1","a".repeat(64),UUID.randomUUID(),1792000000000L);}
 EnchantingPurchasePolicy policy(String version,double base){return new EnchantingPurchasePolicy(version,EnchantingPurchasePolicy.Curve.power(base,1.5),Map.of());}
 @Test void oldCodecRemainsVersionOneAndOldConstructorAndReceiptHashSurvive()throws Exception {
  var old=evidence();var encoded=PurchaseEvidenceCodec.encode(old);
  assertEquals(1,java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).getInt());
  assertNull(PurchaseEvidenceCodec.decode(encoded).xpQuote());assertEquals(old,PurchaseEvidenceCodec.decode(encoded));
  assertEquals(old.payloadHash(),PurchaseEvidenceCodec.decode(encoded).payloadHash());
 }
 @Test void newQuoteRoundTripsAndCannotBeReinterpretedByChangedPolicy()throws Exception {
  var old=evidence();var context=EnchantingPurchaseQuote.Context.of(old);
  var first=old.withXPQuote(policy("table/1",10).quote(context));
  var pending=PurchaseEvidenceCodec.encode(first);var recovered=PurchaseEvidenceCodec.decode(pending);
  var changed=old.withXPQuote(policy("table/2",20).quote(context));
  assertEquals(first,recovered);assertEquals(EnchantingPurchaseXP.baseXP(first),EnchantingPurchaseXP.baseXP(recovered));
  assertNotEquals(changed.payloadHash(),recovered.payloadHash());assertEquals(changed.awardId(),recovered.awardId());
  assertEquals(2,java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(pending)).getInt());
 }
 @Test void quoteCannotBeMovedToAnotherItemTierOperationOrSession(){
  var first=evidence();var other=evidence();var quote=policy("table/1",10).quote(EnchantingPurchaseQuote.Context.of(first));
  assertThrows(IllegalArgumentException.class,()->other.withXPQuote(quote));
 }
}
