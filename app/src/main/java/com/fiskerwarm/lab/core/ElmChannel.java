package com.fiskerwarm.lab.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A single-flight ELM channel. Any timeout makes the whole channel terminal. */
public final class ElmChannel {
    public interface Writer { void write(byte[] bytes) throws Exception; }
    private final Writer writer;
    private final ReadOnlyPolicy policy = new ReadOnlyPolicy();
    private final Object sendLock=new Object(), receiveLock=new Object();
    private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
    private final long sessionDeadline;
    private boolean waiting, ready, closed, monitoring;
    private boolean monitorTruncated;
    private CanCapture.Stream stream;
    private String failure;

    public ElmChannel(Writer writer, long sessionBudgetMillis) {
        if(sessionBudgetMillis <= 0 || sessionBudgetMillis > ReadOnlyPolicy.MAX_SESSION_MILLIS) throw new IllegalArgumentException("Invalid budget");
        this.writer=writer;
        sessionDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(sessionBudgetMillis);
    }
    public String send(String cmd, long timeoutMillis) throws Exception {
        synchronized(sendLock) {
            policy.checkAndRecord(cmd);
            long remaining=TimeUnit.NANOSECONDS.toMillis(sessionDeadline-System.nanoTime());
            if(remaining<=0) { close("Session budget exceeded"); throw new IllegalStateException("Session expired"); }
            if(timeoutMillis<=0 || timeoutMillis>10_000) throw new IllegalArgumentException("Invalid command timeout");
            long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis,remaining));
            synchronized(receiveLock) {
                if(closed) throw new IllegalStateException(failure);
                buffer.reset(); ready=false; waiting=true;
            }
            try {
                writer.write((cmd+"\r").getBytes(StandardCharsets.US_ASCII));
                synchronized(receiveLock) {
                    if(System.nanoTime()>=until) { closed=true; failure="Reply/write deadline exceeded; channel closed"; }
                    while(!ready && !closed) {
                        long nanos=until-System.nanoTime();
                        if(nanos<=0) { closed=true; failure="Reply timeout; channel closed"; break; }
                        TimeUnit.NANOSECONDS.timedWait(receiveLock,nanos);
                    }
                    waiting=false;
                    if(closed) throw new IllegalStateException(failure);
                    String reply=new String(buffer.toByteArray(),StandardCharsets.US_ASCII);
                    int prompt=reply.indexOf('>');
                    if(prompt<0) throw new IllegalStateException("No ELM prompt");
                    String body=reply.substring(0,prompt).trim();
                    if(body.startsWith(cmd)) body=body.substring(cmd.length()).trim();
                    return body;
                }
            } catch(Exception e) { close(e.getMessage()); throw e; }
        }
    }
    /** Passive monitor. Only ATMA is permitted; terminal CR stops adapter monitoring. */
    public String listenCan(long durationMillis) throws Exception {
        if(durationMillis<500 || durationMillis>5000) throw new IllegalArgumentException("Invalid monitor duration");
        synchronized(sendLock) {
            policy.checkAndRecord("ATMA");
            synchronized(receiveLock) {
                if(closed || waiting || monitoring)throw new IllegalStateException("Channel busy/closed");
                buffer.reset();monitorTruncated=false;monitoring=true;
            }
            try {
                writer.write("ATMA\r".getBytes(StandardCharsets.US_ASCII));
                long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(durationMillis);
                synchronized(receiveLock){
                    while(!closed && System.nanoTime()<until && !monitorTruncated) {
                        long nanos=until-System.nanoTime();
                        if(nanos>0)TimeUnit.NANOSECONDS.timedWait(receiveLock,nanos);
                    }
                }
            } finally {
                // Stopping the adapter monitor does not transmit a diagnostic CAN frame.
                try { writer.write("\r".getBytes(StandardCharsets.US_ASCII)); } catch(Exception ignored) { }
                synchronized(receiveLock){monitoring=false;receiveLock.notifyAll();}
            }
            synchronized(receiveLock){
                if(closed)throw new IllegalStateException(failure);
                return new String(buffer.toByteArray(),StandardCharsets.US_ASCII);
            }
        }
    }
    /** Read-only bounded streaming monitor. No raw 48 KB buffer accumulation. */
    public CanCapture.Snapshot listenCanSnapshot(long durationMillis) throws Exception {
        if(durationMillis<500 || durationMillis>5000)throw new IllegalArgumentException("Invalid monitor duration");
        synchronized(sendLock) {
            policy.checkAndRecord("ATMA");
            synchronized(receiveLock) {
                if(closed || waiting || monitoring)throw new IllegalStateException("Channel busy/closed");
                stream=new CanCapture.Stream();monitorTruncated=false;monitoring=true;
            }
            try {
                long monitorStart=System.nanoTime();
                writer.write("ATMA\r".getBytes(StandardCharsets.US_ASCII));
                long until=monitorStart+TimeUnit.MILLISECONDS.toNanos(durationMillis);
                synchronized(receiveLock) {
                    while(!closed && System.nanoTime()<until && !stream.hasAdapterOverflow() && !stream.isTruncated()) {
                        long nanos=until-System.nanoTime();
                        if(nanos>0)TimeUnit.NANOSECONDS.timedWait(receiveLock,nanos);
                    }
                }
            } finally {
                try {writer.write("\r".getBytes(StandardCharsets.US_ASCII));}catch(Exception ignored){}
                synchronized(receiveLock){monitoring=false;receiveLock.notifyAll();}
            }
            synchronized(receiveLock) {
                if(closed)throw new IllegalStateException(failure);
                CanCapture.Snapshot result=stream.finish();
                result.captureElapsedMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-monitorStart);
                stream=null;
                monitorTruncated=result.truncated;
                return result;
            }
        }
    }
    public boolean monitorTruncated() { synchronized(receiveLock){return monitorTruncated;} }

    public void receive(byte[] bytes) {
        synchronized(receiveLock) {
            if((!waiting && !monitoring) || closed) return;
            if(monitoring && stream!=null) {stream.accept(bytes);receiveLock.notifyAll();return;}
            for(byte b:bytes) {
                if((b & 255)>127) { closed=true; failure="Non-ASCII adapter output"; break; }
                if(monitoring) {
                    if(buffer.size()>=48_000) { monitorTruncated=true;break; }
                    buffer.write(b);continue;
                }
                if(buffer.size()>=8192) {closed=true;failure="Malformed or overflowing adapter stream";break;}
                if(ready) {
                    if(b!='\r' && b!='\n' && b!=' ') { closed=true; failure="Unexpected data after ELM prompt"; }
                } else {
                    buffer.write(b);
                    if(b=='>') ready=true;
                }
            }
            receiveLock.notifyAll();
        }
    }
    public void close(String reason) {
        synchronized(receiveLock) { closed=true; waiting=false; failure=reason==null?"Closed":reason; receiveLock.notifyAll(); }
    }
}
