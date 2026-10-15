package com.fiskerwarm.internet;

import java.util.List;

/** Durable receipt state machine. No Bluetooth/CAN dependency or vehicle execution path. */
public final class GatewayEngine {
    public interface Journal {
        Wire.Receipt find(String pair,String id);
        boolean insert(Wire.Command command,Wire.Receipt receipt);
        void replace(Wire.Receipt receipt);
        List<Wire.Command> interrupted(String pair);
    }
    /** Extension boundary: future PKC integration needs an independently verified implementation. */
    public interface ClimatePort {
        String capability();
        boolean canSendToVehicle();
    }
    public static final class BlockedPkcPort implements ClimatePort {
        public String capability(){return "BLOCKED_UNVERIFIED_PKC_21_3";}
        public boolean canSendToVehicle(){return false;}
    }
    private final String pair;
    private final byte[] key;
    private final Journal journal;
    public GatewayEngine(String pair,byte[] key,Journal journal){this.pair=pair;this.key=key.clone();this.journal=journal;}
    public static final class Received {
        public final Wire.Receipt receipt; public final boolean fresh;
        Received(Wire.Receipt r,boolean f){receipt=r;fresh=f;}
    }
    public synchronized Received receive(Wire.Command c,long now) {
        if(!c.authentic(pair,key))throw new SecurityException("COMMAND_SIGNATURE_OR_SCOPE");
        Wire.Receipt old=journal.find(pair,c.id);
        if(old!=null){if(!old.commandMac.equals(c.mac))throw new SecurityException("ID_CONFLICT");return new Received(old,false);}
        Wire.Receipt r=Wire.receipt(c,c.fresh(now)?"DELIVERED":"EXPIRED_REJECTED",now,key);
        if(!journal.insert(c,r)){
            Wire.Receipt concurrent=journal.find(pair,c.id);
            if(concurrent==null)throw new IllegalStateException("DURABLE_COMMIT_FAILED");
            if(!concurrent.commandMac.equals(c.mac))throw new SecurityException("ID_CONFLICT");
            return new Received(concurrent,false);
        }
        return new Received(r,"DELIVERED".equals(r.stage));
    }
    public synchronized Wire.Receipt completeTest(Wire.Command c,long now) {
        if(!c.authentic(pair,key))throw new SecurityException("COMMAND_SIGNATURE_OR_SCOPE");
        Wire.Receipt old=journal.find(pair,c.id);
        if(old==null||!old.commandMac.equals(c.mac))throw new SecurityException("MISSING_JOURNAL");
        if(old.rank()==2)return old;
        Wire.Receipt done=Wire.receipt(c,c.fresh(now)?"TEST_ACCEPTED":"EXPIRED_REJECTED",now,key);
        journal.replace(done); return done;
    }
    public synchronized int recover(long now){
        int count=0;for(Wire.Command c:journal.interrupted(pair)){
            if(!c.authentic(pair,key))throw new SecurityException("JOURNAL_INTEGRITY");
            journal.replace(Wire.receipt(c,"INTERRUPTED_NO_RETRY",Math.max(now,c.issued),key));count++;
        }return count;
    }
}
