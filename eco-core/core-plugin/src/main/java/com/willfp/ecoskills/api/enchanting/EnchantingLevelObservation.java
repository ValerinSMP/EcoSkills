package com.willfp.ecoskills.api.enchanting;

import com.willfp.ecoskills.api.observation.ObservationRequest;
import com.willfp.ecoskills.api.observation.ObservationService;
import java.util.*;

/** Read-only diagnostic of the existing owner cache. No loading getter/defaults/SQL/fallback to zero. */
public record EnchantingLevelObservation(UUID playerId, Integer level, String reason) {
    public EnchantingLevelObservation {
        Objects.requireNonNull(playerId);
        if (level != null && level < 0) throw new IllegalArgumentException("Negative level");
        if ((level == null) == (reason == null)) throw new IllegalArgumentException("Level or absence reason required");
    }
    public boolean available() { return level != null; }
    /** MAIN only, enforced by ObservationService. Does not warm the cached keys. */
    public static EnchantingLevelObservation getCached(UUID uuid) {
        Objects.requireNonNull(uuid);
        return parse(uuid, ObservationService.cached(uuid,
            new ObservationRequest(Set.of(ObservationRequest.Section.SKILLS))).asMap());
    }
    static EnchantingLevelObservation parse(UUID uuid, Map<String,Object> observation) {
        if (!uuid.toString().equals(observation.get("playerUuid"))) return absent(uuid,"identity_mismatch");
        if (!Objects.equals(observation.get("schemaVersion"),2)) return absent(uuid,"schema_unsupported");
        if (Objects.equals(observation.get("status"),"unavailable")) return absent(uuid,reason(observation,"service_unavailable"));
        if (!(observation.get("skills") instanceof Map<?,?> skills) || !(skills.get("items") instanceof List<?> rows))
            return absent(uuid,"skills_unavailable");
        Map<?,?> found=null;
        for (Object value:rows) if(value instanceof Map<?,?> row && Objects.equals(row.get("id"),"enchanting")) {
            if(found!=null)return absent(uuid,"ambiguous_definition"); found=row;
        }
        if(found==null)return absent(uuid,"skill_not_registered");
        if(!(found.get("level") instanceof Map<?,?> field))return absent(uuid,"level_field_missing");
        if(!Objects.equals(field.get("status"),"available"))return absent(uuid,reason(field,"level_unavailable"));
        if(!(field.get("value") instanceof Integer level)||level<0)return absent(uuid,"invalid_value");
        return new EnchantingLevelObservation(uuid,level,null);
    }
    private static String reason(Map<?,?> field,String fallback) {
        return field.get("reason") instanceof String text && !text.isBlank()?text:fallback;
    }
    private static EnchantingLevelObservation absent(UUID uuid,String reason) {
        return new EnchantingLevelObservation(uuid,null,reason);
    }
}
