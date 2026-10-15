package com.fiskerwarm.internet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Internet test protocol. None of these bytes is a vehicle/PKC message. */
public final class Wire {
    private Wire() {}
    public static final String MODE = "TEST_ONLY", TYPE = "CLIMATE_ON_TEST", VEHICLE = "NOT_IMPLEMENTED";
    public static final long TTL = 60, PRESENCE_TTL = 75;
    public static final class Command {
        public final String pair, id, type, mode, mac;
        public final long issued, expires;
        public Command(String p, String i, long t, long e, String y, String m, String s) {
            pair=p; id=i; issued=t; expires=e; type=y; mode=m; mac=s;
        }
        public String text() { return "FW6\nCOMMAND\n"+pair+"\n"+id+"\n"+issued+"\n"+expires+"\n"+type+"\n"+mode; }
        public boolean authentic(String expectedPair, byte[] key) {
            return hex(pair,32) && pair.equals(expectedPair) && hex(id,32) && TYPE.equals(type) && MODE.equals(mode)
                && hex(mac,64) && equal(mac,hmac(key,text())) && issued>=0 && expires>issued && expires-issued<=TTL;
        }
        public boolean fresh(long now) { return now>=0 && issued<=now+10 && expires>now; }
    }
    public static Command command(String pair, byte[] key, long now) {
        Command c=new Command(pair,random(16),now,now+TTL,TYPE,MODE,"");
        return new Command(pair,c.id,now,c.expires,TYPE,MODE,hmac(key,c.text()));
    }
    public static final class Receipt {
        public final String pair,id,commandMac,stage,vehicle,mac;
        public final long at;
        public Receipt(String p,String i,String cm,long t,String s,String v,String m) { pair=p;id=i;commandMac=cm;at=t;stage=s;vehicle=v;mac=m; }
        public String text() { return "FW6\nRECEIPT\n"+pair+"\n"+id+"\n"+commandMac+"\n"+at+"\n"+stage+"\n"+vehicle; }
        public int rank() { return "DELIVERED".equals(stage)?1:("TEST_ACCEPTED".equals(stage)||"EXPIRED_REJECTED".equals(stage)||"INTERRUPTED_NO_RETRY".equals(stage)?2:0); }
        public boolean authentic(Command c,byte[] key) {
            return pair.equals(c.pair)&&id.equals(c.id)&&commandMac.equals(c.mac)&&VEHICLE.equals(vehicle)&&rank()>0&&at>=c.issued-10
                &&hex(mac,64)&&equal(mac,hmac(key,text()));
        }
    }
    public static Receipt receipt(Command c, String stage, long now, byte[] key) {
        Receipt r=new Receipt(c.pair,c.id,c.mac,now,stage,VEHICLE,"");
        if(r.rank()==0) throw new IllegalArgumentException("STAGE");
        return new Receipt(c.pair,c.id,c.mac,now,stage,VEHICLE,hmac(key,r.text()));
    }
    public static String heartbeatText(String pair,long at,String nonce) {return "FW6\nHEARTBEAT\n"+pair+"\n"+at+"\n"+nonce+"\n"+MODE;}
    public static boolean heartbeatValid(String pair,long at,String nonce,String signature,byte[] key,long now) {
        return hex(pair,32)&&hex(nonce,32)&&hex(signature,64)&&at>=0&&Math.abs(now-at)<=PRESENCE_TTL
            &&equal(signature,hmac(key,heartbeatText(pair,at,nonce)));
    }
    public static String hmac(byte[] key,String text) {
        if(key==null||key.length!=32)throw new IllegalArgumentException("KEY_SIZE");
        try {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return encode(m.doFinal(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException("CRYPTO_UNAVAILABLE");}
    }
    public static boolean equal(String a,String b) {return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    public static boolean hex(String s,int n){return s!=null&&s.length()==n&&s.matches("[0-9a-f]+");}
    public static byte[] decode(String s){if(!hex(s,64))throw new IllegalArgumentException("KEY_FORMAT");byte[] b=new byte[32];for(int i=0;i<32;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    public static String encode(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}
    public static String random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return encode(b);}
}
