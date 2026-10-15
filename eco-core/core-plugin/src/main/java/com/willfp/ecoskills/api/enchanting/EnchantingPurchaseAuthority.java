package com.willfp.ecoskills.api.enchanting;
import java.util.*;
import java.util.concurrent.CompletionStage;
/** Implemented by vEnchanting against ITS durable purchase/delivery ledger, never a caller boolean.
 * Entry on MAIN, I/O asynchronous. Return canonical original evidence/attribution from committed rows.
 */
public interface EnchantingPurchaseAuthority {
    int CONTRACT_VERSION=1;
    record Confirmation(String scope,UUID operationId,UUID deliveryReceiptId) {
        public Confirmation{Objects.requireNonNull(operationId);Objects.requireNonNull(deliveryReceiptId);if(scope==null||!scope.matches("[A-Za-z0-9._:/-]{1,96}"))throw new IllegalArgumentException("Scope");}
    }
    enum State { CONFIRMED, NOT_COMMITTED, UNKNOWN, REJECTED }
    record Verification(State state,EnchantingPurchaseEvidence evidence,EnchantingXPConsumer.Attribution originalAttribution,String reason) {
        public Verification{Objects.requireNonNull(state);if(state==State.CONFIRMED?(evidence==null||originalAttribution==null):(evidence!=null||originalAttribution!=null))throw new IllegalArgumentException("Evidence availability");}
    }
    CompletionStage<Verification> verifyConfirmedPurchase(Confirmation confirmation);
}
