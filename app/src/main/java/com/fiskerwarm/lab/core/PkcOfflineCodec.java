package com.fiskerwarm.lab.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * Independent OFFLINE protocol fixtures from the OceanLink Pro 1.2.6 static
 * compatibility report (2026-10-12). No BLE transport, key provisioning,
 * vehicle-wake or transmission. Bytes here are NOT complete wire messages.
 * Does not use decompiled application source or bundle a third-party SDK.
 */
public final class PkcOfflineCodec {
    private PkcOfflineCodec() {}
    public static final String EVIDENCE = "STATIC_SDK_ONLY_NOT_VALIDATED_ON_OWNER_PKC";
    public static final String PROVISIONING = "BLOCKED_OWNER_KEY";
    private static final int[] CLIMATE_DURATIONS = {5,10,15,20,30,45,60,90,120};

    /** A compatibility fixture, not a verified whole BLE wire frame. */
    public static byte[] climateStartPayload(int minutes) {
        boolean accepted=false;
        for (int v : CLIMATE_DURATIONS) if(v==minutes) {accepted=true;break;}
        if(!accepted) throw new IllegalArgumentException("Unsupported SDK duration");
        return new byte[]{0x31,0x01,(byte)0xFD,0x03,0x00,(byte)minutes};
    }

    /** A compatibility fixture, not a verified whole BLE wire frame. */
    public static byte[] climateStopPayload() {
        return new byte[]{0x31,0x01,(byte)0xFD,0x03,0x01,0x00};
    }

    public enum Zone { DRIVER, PASSENGER }

    /** Only the statically documented 18-32 C half-degree range is supported. */
    public static byte[] setpointPayload(Zone zone, double celsius) {
        if(zone==null || !Double.isFinite(celsius) || celsius<18 || celsius>32 ||
                celsius*2 != Math.rint(celsius*2)) {
            throw new IllegalArgumentException("Setpoint must be 18..32 C, in 0.5 C steps");
        }
        int raw=(int)((celsius-18)*2)+36;
        return new byte[]{0x31,0x01,(byte)0xFD,0x08,(byte)(zone==Zone.DRIVER?0:1),(byte)raw};
    }

    /** This is format checking only; NOT authorization or key retrieval. */
    public static boolean looksLikeVaKey(String candidate) {
        if(candidate==null || candidate.length()!=32) return false;
        for(int i=0;i<candidate.length();i++) {
            char c=candidate.charAt(i);
            if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F'))) return false;
        }
        return true;
    }

    /** Pure crypto primitive for deterministic TEST VECTORS ONLY. */
    public static byte[] hmacSha256(byte[] key, byte[] message) {
        if(key==null || key.length==0 || message==null) throw new IllegalArgumentException("Null/empty HMAC input");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable",e);
        }
    }

    /** Converts binary evidence to deterministic uppercase HEX; never log real keys. */
    public static String hex(byte[] bytes) {
        if(bytes==null) throw new IllegalArgumentException("Missing byte data");
        final char[] digits="0123456789ABCDEF".toCharArray();
        char[] s=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++) {int v=bytes[i]&0xFF;s[2*i]=digits[v>>>4];s[2*i+1]=digits[v&15];}
        return new String(s);
    }

    /** Independent status-field interpretation, NOT a whole packet decoder. */
    public static final class StatusField {
        public final int id;
        public final String name;
        public final double interpretedValue;
        public final String unit;
        private StatusField(int id,String name,double value,String unit) {
            this.id=id;this.name=name;this.interpretedValue=value;this.unit=unit;
        }
    }
    public static StatusField interpretStatusField(int code, int raw) {
        if(raw<0 || raw>255) throw new IllegalArgumentException("Out-of-range status byte");
        switch(code) {
            case 0x57: return new StatusField(code,"driverSetpoint",raw/2.0,"C");
            case 0x58: return new StatusField(code,"passengerSetpoint",raw/2.0,"C");
            case 0x59: return new StatusField(code,"fanSpeedRaw",raw,"raw");
            case 0x5A: return new StatusField(code,"cabinTemp",raw/2.0-48,"C");
            case 0x5B: return new StatusField(code,"ambientTemp",raw/2.0-48,"C");
            case 0x5F: return new StatusField(code,"undecodedFlags",raw,"bitmask");
            default: throw new IllegalArgumentException("Undocumented status ID");
        }
    }

    /** Always fail-closed until owner-authorized setup and vehicle validation. */
    public static boolean canSendToVehicle() { return false; }
}
