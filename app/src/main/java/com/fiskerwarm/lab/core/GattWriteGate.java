package com.fiskerwarm.lab.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One host GATT write at a time. A failed/timed-out operation is terminal. No retry. */
public final class GattWriteGate {
    public interface Backend { int queue(byte[] value) throws Exception; }
    public interface Trace { void event(String kind,long id,byte[] value,int status); }
    private final Object lock=new Object();
    private final Trace trace;
    private Ticket pending;
    private boolean closed;
    private long next;
    private static final class Ticket {
        final long id;
        final byte[] value;
        final CountDownLatch done=new CountDownLatch(1);
        int status=-1;
        boolean completed;
        Ticket(long id,byte[] value){this.id=id;this.value=value.clone();}
    }
    public GattWriteGate(Trace trace){this.trace=trace;}
    public void write(byte[] value,long timeoutMillis,Backend backend) throws Exception {
        if(value==null || value.length==0 || timeoutMillis<=0)throw new IllegalArgumentException("Invalid write");
        final Ticket t;
        synchronized(lock){
            if(closed || pending!=null)throw new IllegalStateException("GATT write busy/closed");
            pending=t=new Ticket(++next,value);
        }
        try {
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            trace.event("TX_REQUEST",t.id,t.value,-1);
            int queued=backend.queue(t.value.clone());
            trace.event("TX_QUEUE_RESULT",t.id,t.value,queued);
            if(queued!=0)throw new IllegalStateException("GATT write rejected: "+queued);
            long remaining=deadline-System.nanoTime();
            if(remaining<=0 || !t.done.await(remaining,TimeUnit.NANOSECONDS))throw new IllegalStateException("GATT write callback timeout");
            synchronized(lock){if(closed || !t.completed || t.status!=0)throw new IllegalStateException("GATT write failed: "+t.status);}
            trace.event("TX_COMPLETE",t.id,t.value,t.status);
        } catch(Exception e) {
            synchronized(lock){closed=true;}
            trace.event("TX_FAILED",t.id,t.value,t.status);
            throw e;
        } finally {synchronized(lock){if(pending==t)pending=null;}}
    }
    /** Call only after checking connection and characteristic identity. */
    public void complete(int status) {
        synchronized(lock){
            Ticket t=pending;
            if(closed || t==null || t.completed){trace.event("UNEXPECTED_WRITE_CALLBACK",0,new byte[0],status);return;}
            t.status=status;t.completed=true;
            trace.event("TX_CALLBACK",t.id,t.value,status);
            t.done.countDown();
        }
    }
    public void close(){synchronized(lock){closed=true;if(pending!=null)pending.done.countDown();}}
}
