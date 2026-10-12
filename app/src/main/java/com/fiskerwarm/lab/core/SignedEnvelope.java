package com.fiskerwarm.lab.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Offline gateway security primitive. No HTTP server or vehicle writer is shipped. */
public final class SignedEnvelope {
    public interface ReplayLedger {
        /** Atomically persist nonce until expiry. Fail closed if persistence fails. */
        boolean claim(String nonce, long expirySeconds) throws Exception;
    }
    private SignedEnvelope() {}
    public static byte[] sign(byte[] key, String method, String path, long seconds, String nonce, byte[] body) throws Exception {
        validate(key,method,path,nonce,body);
        String header="FISKER-WARM-1\n"+method+"\n"+path+"\n"+seconds+"\n"+nonce+"\n";
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key,"HmacSHA256"));
        mac.update(header.getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(body);
    }
    public static boolean verify(byte[] key,String method,String path,long seconds,String nonce,byte[] body,
                                 byte[] signature,long now,ReplayLedger ledger) {
        try {
            if(ledger==null || now<0 || seconds<0 || seconds < now-30 || seconds > now+30 ||
                    signature==null || signature.length!=32) return false;
            if(!MessageDigest.isEqual(sign(key,method,path,seconds,nonce,body),signature)) return false;
            return ledger.claim(nonce,seconds+31);
        } catch(Exception e) { return false; }
    }
    private static void validate(byte[] key,String method,String path,String nonce,byte[] body) {
        if(key==null || key.length!=32 || !"POST".equals(method) || !"/v1/heat-request".equals(path) ||
                nonce==null || !nonce.matches("[0-9a-f]{32}") || body==null || body.length>4096) {
            throw new IllegalArgumentException("Invalid envelope fields");
        }
    }
    public static String hex(byte[] b) {
        StringBuilder s=new StringBuilder();
        for(byte x:b) s.append(String.format(Locale.ROOT,"%02x",x & 255));
        return s.toString();
    }
}
