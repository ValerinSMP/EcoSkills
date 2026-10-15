package com.willfp.ecoskills.api.enchanting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, versioned claim of a confirmed purchase. Structural validity does NOT
 * authenticate application/delivery: a future provider must verify the owner's durable receipt.
 * Draft contract: no awarding capability is registered by these types.
 */
public record EnchantingPurchaseEvidence(
        UUID operationId, UUID playerId, String scope, UUID sessionId,
        String enchantmentKey, int fromTier, int toTier, Mode mode,
        int vanillaLevelsConsumed, String tariffVersion, String contextHash,
        UUID deliveryReceiptId, long confirmedAtEpochMillis) {
    public static final int SCHEMA_VERSION = 1;
    public enum Mode { VANILLA_XP, CUSTOM_SHARDS, CUSTOM_GEMS, VANILLA_OVERLEVEL_XP_SHARDS }
    public EnchantingPurchaseEvidence {
        Objects.requireNonNull(operationId); Objects.requireNonNull(playerId);
        Objects.requireNonNull(sessionId); Objects.requireNonNull(mode);
        Objects.requireNonNull(deliveryReceiptId);
        scope = token(scope, "scope");
        tariffVersion = token(tariffVersion, "tariffVersion");
        Objects.requireNonNull(enchantmentKey);
        if (!enchantmentKey.matches("[a-z0-9._-]+:[a-z0-9/._-]+") || enchantmentKey.length() > 128)
            throw new IllegalArgumentException("Invalid enchantmentKey");
        if (fromTier < 0 || toTier <= fromTier || toTier > 32767)
            throw new IllegalArgumentException("Purchase must increase the tier");
        if (vanillaLevelsConsumed < 0 || confirmedAtEpochMillis <= 0)
            throw new IllegalArgumentException("Invalid consumption/confirmation");
        boolean custom = mode == Mode.CUSTOM_SHARDS || mode == Mode.CUSTOM_GEMS;
        if (custom ? vanillaLevelsConsumed != 0 : vanillaLevelsConsumed == 0)
            throw new IllegalArgumentException("Consumption does not match mode");
        Objects.requireNonNull(contextHash);
        if (!contextHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid contextHash");
    }
    private static String token(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z0-9._:/-]{1,96}")) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
    /** Stable across JVMs/retries; all bound payload fields participate. Not an authentication signature. */
    public String payloadHash() {
        String[] fields = {Integer.toString(SCHEMA_VERSION), operationId.toString(), playerId.toString(), scope,
            sessionId.toString(), enchantmentKey, Integer.toString(fromTier), Integer.toString(toTier), mode.name(),
            Integer.toString(vanillaLevelsConsumed), tariffVersion, contextHash, deliveryReceiptId.toString(),
            Long.toString(confirmedAtEpochMillis)};
        MessageDigest hash = sha256();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    /** Same operation identity produces the same award ID; payload conflicts MUST be rejected separately. */
    public UUID awardId() {
        byte[] bytes = sha256().digest(("ecoskills:enchanting-purchase:v1/" + scope + "/" + operationId)
            .getBytes(StandardCharsets.UTF_8));
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80); // RFC 9562 version 8: application-specific digest.
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer data = ByteBuffer.wrap(bytes);
        return new UUID(data.getLong(), data.getLong());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
