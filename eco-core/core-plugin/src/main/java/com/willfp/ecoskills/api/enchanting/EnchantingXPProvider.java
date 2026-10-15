package com.willfp.ecoskills.api.enchanting;

import java.util.*;
import org.bukkit.plugin.Plugin;
import java.util.concurrent.CompletionStage;

/** Durable receipt observer. No grant, SQL, pool or profile access is exposed.
 * Obtain a scoped Access from the provider's authorized consumer registration.
 * All methods return immediately; SQL never runs on the caller thread.
 * Timeout/UNKNOWN never authorizes a new operation or award identity.
 */
public interface EnchantingXPProvider {
    int CONTRACT_VERSION = 1;
    record Readiness(boolean ready, int providerVersion, int consumerVersion, Set<String> blockers) {
        public Readiness {
            blockers=Set.copyOf(blockers);
            if(ready != blockers.isEmpty())throw new IllegalArgumentException("Readiness/blockers mismatch");
        }
    }
    record ReceiptKey(String scope, UUID operationId, UUID awardId) {
        public ReceiptKey {
            Objects.requireNonNull(operationId);Objects.requireNonNull(awardId);
            if(scope==null||!scope.matches("[A-Za-z0-9._:/-]{1,96}"))throw new IllegalArgumentException("Scope");
        }
    }
    /** Only constructed from committed provider state; finalXP may be zero (cap exhausted).
     * A committed receipt alone is NOT a currently claimed delivery attempt.
     */
    record CommittedReceipt(EnchantingPurchaseEvidence evidence, UUID awardId, double finalXP,
            EnchantingXPConsumer.Attribution originalAttribution, String receiptPayloadHash,
            int nativeLevel, double nativeXP, long revision) {
        public CommittedReceipt {
            Objects.requireNonNull(evidence);Objects.requireNonNull(originalAttribution);
            if(!evidence.awardId().equals(awardId)||!Double.isFinite(finalXP)||finalXP<0||finalXP>EnchantingPurchaseXP.DAILY_LIMIT||
                !Double.isFinite(nativeXP)||nativeXP<0||nativeLevel<0||revision<0||
                receiptPayloadHash==null||!receiptPayloadHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Receipt");
            var a=originalAttribution.request();
            if(!a.operationId().equals(evidence.operationId())||!a.playerId().equals(evidence.playerId())||
                !a.scope().equals(evidence.scope())||!a.originalSession().equals(evidence.sessionId()))throw new IllegalArgumentException("Attribution mismatch");
        }
    }
    enum State { APPLIED, NOT_FOUND, PENDING, UNKNOWN, REJECTED, PAYLOAD_CONFLICT, UNAVAILABLE }
    record Observation(State state, String reason, CommittedReceipt receipt) {
        public Observation {
            Objects.requireNonNull(state);
            if((state==State.APPLIED)!=(receipt!=null))throw new IllegalArgumentException("Receipt availability");
        }
    }
    enum Ack { ACKED, ALREADY_ACKED, STALE_ATTEMPT, NOT_COMMITTED, PAYLOAD_CONFLICT, REJECTED, UNKNOWN, UNAVAILABLE }
    Readiness readiness();
    /** Registration is permitted only for the exact plugin instance configured by trusted bootstrap.
     * Neither a matching plugin name nor knowledge of awardId authenticates a consumer.
     */
    Access registerConsumer(Plugin owner, EnchantingXPConsumer consumer);
    void unregisterConsumer(Plugin owner, EnchantingXPConsumer consumer);
    /** Capability is bound to an authorized owner/scope. It must not be shared with other plugins. */
    interface Access {
        CompletionStage<Observation> lookupReceipt(ReceiptKey key);
        /** Reconstructs all fields from durable rows AND verifies the current unexpired attempt. */
        CompletionStage<Observation> verifyDelivery(EnchantingXPConsumer.Delivery delivery);
        /** APPLIED/ALREADY_APPLIED must mean the consumer's OWN transaction committed.
         * The provider cannot inspect another plugin's DB. RETRY/REVIEW/CONFLICT never acknowledge.
         */
        CompletionStage<Ack> acknowledgeDelivery(EnchantingXPConsumer.Delivery originalDelivery,
                                               EnchantingXPConsumer.Consumption committedResult);
    }
}
