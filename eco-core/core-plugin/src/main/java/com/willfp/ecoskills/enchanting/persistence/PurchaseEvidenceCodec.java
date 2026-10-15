package com.willfp.ecoskills.enchanting.persistence;

import com.willfp.ecoskills.api.enchanting.EnchantingPurchaseEvidence;
import java.io.*;
import java.util.*;

/** Bounded, versioned durable encoding. No Java serialization, Bukkit, or credentials. */
public final class PurchaseEvidenceCodec {
    private PurchaseEvidenceCodec() {}
    public static String encode(EnchantingPurchaseEvidence e) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(1);
            for(String value:List.of(e.operationId().toString(),e.playerId().toString(),e.scope(),e.sessionId().toString(),e.enchantmentKey(),
                Integer.toString(e.fromTier()),Integer.toString(e.toTier()),e.mode().name(),Integer.toString(e.vanillaLevelsConsumed()),
                e.tariffVersion(),e.contextHash(),e.deliveryReceiptId().toString(),Long.toString(e.confirmedAtEpochMillis())))out.writeUTF(value);
            out.flush();return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(IOException impossible){throw new AssertionError(impossible);}
    }
    public static EnchantingPurchaseEvidence decode(String encoded) throws IOException {
        if(encoded==null||encoded.length()>8192)throw new IOException("Evidence size");
        try {
            byte[] bytes=Base64.getDecoder().decode(encoded);var in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=1)throw new IOException("Evidence version");
            var e=new EnchantingPurchaseEvidence(UUID.fromString(in.readUTF()),UUID.fromString(in.readUTF()),in.readUTF(),UUID.fromString(in.readUTF()),
                in.readUTF(),Integer.parseInt(in.readUTF()),Integer.parseInt(in.readUTF()),EnchantingPurchaseEvidence.Mode.valueOf(in.readUTF()),
                Integer.parseInt(in.readUTF()),in.readUTF(),in.readUTF(),UUID.fromString(in.readUTF()),Long.parseLong(in.readUTF()));
            if(in.available()!=0)throw new IOException("Trailing evidence data");return e;
        }catch(IllegalArgumentException e){throw new IOException("Invalid evidence",e);}
    }
}
