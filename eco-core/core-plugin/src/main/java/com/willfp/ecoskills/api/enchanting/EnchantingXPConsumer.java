package com.willfp.ecoskills.api.enchanting;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Consumer coordination contract only; runtime provider registration is not enabled by this interface.
 * capture and consume are invoked on MAIN; implementations copy Bukkit context there and perform I/O asynchronously.
 * Returning APPLIED/ALREADY_APPLIED means the consumer's OWN durable transaction has committed.
 */
public interface EnchantingXPConsumer {
    int CONTRACT_VERSION = 1;
    record AttributionRequest(UUID operationId,UUID playerId,String scope,UUID originalSession) {
        public AttributionRequest {
            Objects.requireNonNull(operationId);Objects.requireNonNull(playerId);Objects.requireNonNull(originalSession);
            if(scope==null||!scope.matches("[A-Za-z0-9._:/-]{1,96}"))throw new IllegalArgumentException("Scope");
        }
    }
    record Attribution(UUID captureId,AttributionRequest request,String opaqueToken) {
        public Attribution {
            Objects.requireNonNull(captureId);Objects.requireNonNull(request);Objects.requireNonNull(opaqueToken);
            if(opaqueToken.isBlank()||opaqueToken.length()>4096)throw new IllegalArgumentException("Attribution token");
        }
    }
    record Delivery(EnchantingPurchaseEvidence evidence, UUID awardId,double finalXP,
                    Attribution originalAttribution,UUID deliveryAttempt,String receiptPayloadHash) {
        public Delivery {
            Objects.requireNonNull(evidence);Objects.requireNonNull(awardId);Objects.requireNonNull(originalAttribution);Objects.requireNonNull(deliveryAttempt);
            if(!Double.isFinite(finalXP)||finalXP<=0||finalXP>EnchantingPurchaseXP.DAILY_LIMIT)throw new IllegalArgumentException("Final XP");
            if(!awardId.equals(evidence.awardId()))throw new IllegalArgumentException("Award identity");
            var original=originalAttribution.request();
            if(!original.playerId().equals(evidence.playerId())||!original.operationId().equals(evidence.operationId())||
               !original.scope().equals(evidence.scope())||!original.originalSession().equals(evidence.sessionId()))
                throw new IllegalArgumentException("Original attribution does not belong to purchase");
            if(receiptPayloadHash==null||!receiptPayloadHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Receipt hash");
        }
    }
    enum Consumption { APPLIED, ALREADY_APPLIED, RETRY, REVIEW, PAYLOAD_CONFLICT }
    /** BEFORE checkout/application; token must capture the original pet distribution, including known empty selection. */
    CompletionStage<Attribution> capture(AttributionRequest request);
    /** Only after verification of the provider's committed receipt; never select the currently equipped pet here. */
    CompletionStage<Consumption> consume(Delivery delivery);
}
