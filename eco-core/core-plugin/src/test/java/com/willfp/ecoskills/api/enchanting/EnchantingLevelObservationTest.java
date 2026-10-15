package com.willfp.ecoskills.api.enchanting;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EnchantingLevelObservationTest {
    private final UUID uuid=UUID.randomUUID();
    private Map<String,Object> observation(Map<String,Object> field) {
        return Map.of("schemaVersion",2,"playerUuid",uuid.toString(),"status","partial",
            "skills",Map.of("items",List.of(Map.of("id","enchanting","level",field))));
    }
    @Test void availableZeroAndPositiveAreActualValues() {
        for(int n:new int[]{0,14}) {
            var answer=EnchantingLevelObservation.parse(uuid,observation(Map.of("value",n,"status","available")));
            assertTrue(answer.available()); assertEquals(n,answer.level()); assertNull(answer.reason());
        }
    }
    @Test void fieldReasonSurvivesInsteadOfGenericLevelMissing() {
        for(String reason:List.of("cache_cold","profile_not_loaded","invalid_value")) {
            var answer=EnchantingLevelObservation.parse(uuid,observation(Map.of("status","unavailable","reason",reason)));
            assertFalse(answer.available());assertNull(answer.level());assertEquals(reason,answer.reason());
        }
    }
    @Test void rootReasonAndIdentityAreVerified() {
        assertEquals("cache_api_unavailable",EnchantingLevelObservation.parse(uuid,Map.of("schemaVersion",2,"playerUuid",uuid.toString(),"status","unavailable","reason","cache_api_unavailable")).reason());
        assertEquals("identity_mismatch",EnchantingLevelObservation.parse(UUID.randomUUID(),observation(Map.of("value",14,"status","available"))).reason());
    }
    @Test void fractionalAndNegativeInputsDoNotInventLevels() {
        for(Object value:List.of(0.02,-1,14.0,"14")) assertEquals("invalid_value",EnchantingLevelObservation.parse(uuid,observation(Map.of("value",value,"status","available"))).reason());
    }
}
