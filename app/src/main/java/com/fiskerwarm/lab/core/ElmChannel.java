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
    private boolean retainMonitorRaw;
    private static final long STOP_DRAIN_MILLIS=1000;
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
    /** Legacy raw view uses the same prompt/stop state machine as streaming capture. */
    public String listenCan(long durationMillis) throws Exception {
        synchronized(sendLock) {
            monitor(durationMillis,true);
            synchronized(receiveLock) {return new String(buffer.toByteArray(),StandardCharsets.US_ASCII);}
        }
    }
    /** Finite acquisition followed by bounded prompt drain; no increase to the CAN window. */
    public CanCapture.Snapshot listenCanSnapshot(long durationMillis) throws Exception {
        synchronized(sendLock) {return monitor(durationMillis,false);}
    }
    private CanCapture.Snapshot monitor(long durationMillis,boolean retainRaw) throws Exception {
        if(durationMillis<500 || durationMillis>5000)throw new IllegalArgumentException("Invalid monitor duration");
        policy.checkAndRecord("ATMA");
        long monitorStart=System.nanoTime();
        long until=Math.min(sessionDeadline,monitorStart+TimeUnit.MILLISECONDS.toNanos(durationMillis));
        synchronized(receiveLock) {
            if(closed || waiting || monitoring)throw new IllegalStateException("Channel busy/closed");
            if(monitorStart>=sessionDeadline) {closed=true;failure="Session budget exceeded";throw new IllegalStateException(failure);}
            stream=new CanCapture.Stream(monitorStart);buffer.reset();retainMonitorRaw=retainRaw;
            monitorTruncated=false;monitoring=true;
        }
        String reason, stopError=null;
        boolean stopSent=false, stopWritten=false;
        long acquisitionEnd;
        try {
            writer.write("ATMA\r".getBytes(StandardCharsets.US_ASCII));
            long startWriteDone=System.nanoTime();
            synchronized(receiveLock) {
                while(!closed && !stream.hasPrompt() && !stream.hasAdapterOverflow() && !stream.hasAdapterError()
                        && !stream.hasStopped() && !stream.isTruncated() && !monitorTruncated && System.nanoTime()<until) {
                    TimeUnit.NANOSECONDS.timedWait(receiveLock,until-System.nanoTime());
                }
                if(closed)throw new IllegalStateException(failure);
                acquisitionEnd=System.nanoTime();
                reason=stream.hasAdapterOverflow()?"BUFFER_FULL":stream.hasAdapterError()?"ADAPTER_ERROR":
                        stream.hasPrompt()?"ADAPTER_PROMPT":stream.hasStopped()?"ADAPTER_STOPPED":
                        (stream.isTruncated() || monitorTruncated)?"APP_TRUNCATED":
                        acquisitionEnd>=sessionDeadline?"SESSION_LIMIT":"WINDOW_ELAPSED";
                // An empty CR at an idle prompt REPEATS ATMA. Do not restart a
                // monitor that has already ended, including BUFFER FULL/error.
                stopSent=!stream.hasPrompt() && !stream.hasAdapterOverflow() && !stream.hasAdapterError()
                        && !stream.hasStopped() && acquisitionEnd<sessionDeadline;
            }
            if(stopSent) {
                try {writer.write("\r".getBytes(StandardCharsets.US_ASCII));stopWritten=true;}
                catch(Exception e) {stopError=e.toString();}
            }
            long drainUntil=Math.min(sessionDeadline,acquisitionEnd+TimeUnit.MILLISECONDS.toNanos(STOP_DRAIN_MILLIS));
            synchronized(receiveLock) {
                while(!closed && stopError==null && !stream.hasPrompt() && System.nanoTime()<drainUntil) {
                    TimeUnit.NANOSECONDS.timedWait(receiveLock,drainUntil-System.nanoTime());
                }
                if(closed)throw new IllegalStateException(failure);
                CanCapture.Snapshot result=stream.finish();
                result.captureElapsedMillis=TimeUnit.NANOSECONDS.toMillis(acquisitionEnd-monitorStart);
                result.startWriteMillis=TimeUnit.NANOSECONDS.toMillis(startWriteDone-monitorStart);
                result.lateStartWrite=startWriteDone>=monitorStart+TimeUnit.MILLISECONDS.toNanos(durationMillis);
                result.windowElapsed=acquisitionEnd>=monitorStart+TimeUnit.MILLISECONDS.toNanos(durationMillis);
                result.stopDrainMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-acquisitionEnd);
                result.endReason=reason;result.stopSent=stopSent;result.stopWriteConfirmed=stopWritten;
                result.stopError=stopError;result.stopConfirmed=result.promptSeen && stopError==null;
                result.sessionBudgetExceeded=System.nanoTime()>=sessionDeadline;
                result.truncated|=monitorTruncated;
                monitorTruncated=result.truncated;
                if(!result.stopConfirmed || result.truncated) {
                    closed=true;failure=stopError==null?"Unconfirmed or malformed monitor completion; channel closed":stopError;
                }
                return result;
            }
        } catch(Exception e) {
            close(e.getMessage());throw e;
        } finally {
            synchronized(receiveLock) {monitoring=false;stream=null;retainMonitorRaw=false;receiveLock.notifyAll();}
        }
    }
    public boolean monitorTruncated() { synchronized(receiveLock){return monitorTruncated;} }

    public void receive(byte[] bytes) {
        synchronized(receiveLock) {
            if((!waiting && !monitoring) || closed) return;
            if(monitoring && stream!=null) {
                stream.accept(bytes);
                if(retainMonitorRaw)for(byte b:bytes) {
                    if(buffer.size()>=48_000) {monitorTruncated=true;break;}
                    buffer.write(b);
                }
                receiveLock.notifyAll();return;
            }
            for(byte b:bytes) {
                if(b==0)continue; // documented ELM serial NULL insertion, not a frame byte
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
