package com.willfp.ecoskills.api.enchanting;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EnchantingXPConsumerTest {
    @Test void deliveryRejectsAChangedPlayerSessionOrAward() {
        UUID operation=UUID.randomUUID(),player=UUID.randomUUID(),session=UUID.randomUUID();
        var evidence=new EnchantingPurchaseEvidence(operation,player,"survival",session,"ecoenchants:lifesteal",3,4,EnchantingPurchaseEvidence.Mode.CUSTOM_SHARDS,0,"prices/1","a".repeat(64),UUID.randomUUID(),1792000000000L);
        var context=new EnchantingXPConsumer.AttributionRequest(operation,player,"survival",session);
        var capture=new EnchantingXPConsumer.Attribution(UUID.randomUUID(),context,"captured-original-pets");
        var delivery=new EnchantingXPConsumer.Delivery(evidence,evidence.awardId(),51.43928459844674,capture,UUID.randomUUID(),"b".repeat(64));
        assertEquals(context,delivery.originalAttribution().request());
        assertThrows(IllegalArgumentException.class,()->new EnchantingXPConsumer.Delivery(evidence,UUID.randomUUID(),51,capture,UUID.randomUUID(),"b".repeat(64)));
        var changed=new EnchantingXPConsumer.Attribution(UUID.randomUUID(),new EnchantingXPConsumer.AttributionRequest(operation,player,"survival",UUID.randomUUID()),"different-session");
        assertThrows(IllegalArgumentException.class,()->new EnchantingXPConsumer.Delivery(evidence,evidence.awardId(),51,changed,UUID.randomUUID(),"b".repeat(64)));
    }
}
