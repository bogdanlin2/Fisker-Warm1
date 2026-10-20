package com.fiskerwarm.lab.core;

/** Receive-only interpretation of the pinned, derived FM29 V390.1 matrix. No encoder. */
public final class HvacDecoder {
    public static final String SOURCE_COMMIT="68a88af90b369c1b2c55d7949c4c848f41deb857";
    public static int[] decode(String id,byte[] p) {
        if(p==null || p.length!=8)throw new IllegalArgumentException("HVAC needs exactly eight bytes");
        if("534".equals(id))return new int[]{p[7]&3};
        if("373".equals(id))return new int[]{p[0]&15,(p[1]>>>1)&15,p[4]&1,(p[7]>>>3)&3};
        throw new IllegalArgumentException("Only 534 and 373 have a scoped decoder");
    }
    public static String switchLabel(int v) {
        return v==0?"неактивний":v==1?"запит ON":v==2?"запит OFF":"зарезервовано";
    }
    public static String describe(String id,byte[] p) {
        int[] v=decode(id,p);
        if("534".equals(id))return "ICC_ECCSysSwtCmd="+v[0]+" · "+switchLabel(v[0]);
        return "ECC_CrtSts="+v[0]+" · вентилятор="+v[1]+" · Heat="+v[2]+" · Remote="+v[3];
    }
    private HvacDecoder() {}
}
