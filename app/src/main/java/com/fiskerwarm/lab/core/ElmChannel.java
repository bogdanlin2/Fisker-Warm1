package com.fiskerwarm.lab.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;

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
    private boolean diagnosticOnly;
    private final boolean hvacOnly;
    private final boolean canRxAudit;
    private final Set<String> hvacAcknowledged=new HashSet<>();
    private int hvacTarget=-1;
    private String hvacFilterMethod;
    private boolean stopRequested,hvacResetOnly;
    private CanCapture.Snapshot lastSnapshot;
    private static final long STOP_DRAIN_MILLIS=1000;
    private CanCapture.Stream stream;
    private String failure;

    public ElmChannel(Writer writer, long sessionBudgetMillis) {
        this(writer,sessionBudgetMillis,false,false);
    }
    private ElmChannel(Writer writer,long sessionBudgetMillis,boolean hvacOnly,boolean canRxAudit) {
        if(sessionBudgetMillis <= 0 || sessionBudgetMillis > ReadOnlyPolicy.MAX_SESSION_MILLIS) throw new IllegalArgumentException("Invalid budget");
        this.writer=writer;
        this.hvacOnly=hvacOnly;
        this.canRxAudit=canRxAudit;
        sessionDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(sessionBudgetMillis);
    }
    public static ElmChannel forHvac(Writer writer,long budgetMillis) {return new ElmChannel(writer,budgetMillis,true,false);}
    public static ElmChannel forCanRxAudit(Writer writer,long budgetMillis) {return new ElmChannel(writer,budgetMillis,true,true);}
    public String send(String cmd, long timeoutMillis) throws Exception {
        synchronized(sendLock) {
            if(hvacOnly) {
                if(!(canRxAudit?PassiveNetworkPlan.canRxAuditCommand(cmd):PassiveNetworkPlan.hvacAdapterCommand(cmd)) || (hvacResetOnly && !"ATZ".equals(cmd)))
                    throw new SecurityException("HVAC channel permits fixed passive adapter settings only");
                return exchange(cmd,timeoutMillis);
            }
            if(diagnosticOnly && !PassiveNetworkPlan.safeStartupCommand(cmd))
                throw new SecurityException("Network audit channel cannot send ECU requests or enable flow control");
            policy.checkAndRecord(cmd);
            return exchange(cmd,timeoutMillis);
        }
    }
    /** Separate fixed adapter receive/status operations; protected vehicle policy stays unchanged. */
    public String sendAdapterDiagnostic(String cmd,long timeoutMillis) throws Exception {
        synchronized(sendLock) {
            if(hvacOnly)throw new SecurityException("Legacy diagnostics cannot reuse an HVAC channel");
            if(!PassiveNetworkPlan.diagnosticAdapterCommand(cmd))throw new SecurityException("Not a fixed receive-only adapter diagnostic");
            diagnosticOnly=true; // No vehicle/UDS path can reuse this channel afterwards.
            return exchange(cmd,timeoutMillis);
        }
    }
    private String exchange(String cmd,long timeoutMillis) throws Exception {
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
                if(hvacOnly)noteHvacReply(cmd,body);
                return body;
            }
        } catch(Exception e) { close(e.getMessage()); throw e; }
    }
    private void noteHvacReply(String cmd,String reply) {
        if("ATZ".equals(cmd)) {hvacAcknowledged.clear();hvacTarget=-1;hvacFilterMethod=null;hvacResetOnly=false;return;}
        if("ATAR".equals(cmd) || "ATCM7FF".equals(cmd)) {
            hvacTarget=-1;hvacFilterMethod=null;hvacAcknowledged.remove("ATCM7FF");
        }
        if(cmd.startsWith("ATCF") || cmd.startsWith("ATCRA")){hvacTarget=-1;hvacFilterMethod=null;}
        if("OK".equals(reply.trim())) {
            hvacAcknowledged.add(cmd);
            if("ATCF534".equals(cmd) || "ATCF373".equals(cmd) || (canRxAudit && "ATCF0E9".equals(cmd))) {
                if(hvacAcknowledged.contains("ATCM7FF")){hvacTarget=Integer.parseInt(cmd.substring(4),16);hvacFilterMethod="CM_CF";}
            } else if(canRxAudit && ("ATCRA0E9".equals(cmd) || "ATCRA373".equals(cmd))) {
                hvacTarget=Integer.parseInt(cmd.substring(5),16);hvacFilterMethod="CRA";
            }
        } else hvacAcknowledged.remove(cmd);
        if("ATDPN".equals(cmd) && "6".equals(reply.trim()))hvacAcknowledged.add("PROTOCOL_6");
    }
    private void requireHvacMonitorReady() {
        if(hvacResetOnly || (hvacTarget!=0x534 && hvacTarget!=0x373 && !(canRxAudit && hvacTarget==0x0E9)))throw new SecurityException("Exact filter not armed");
        if(!("CM_CF".equals(hvacFilterMethod) && hvacAcknowledged.contains("ATCM7FF")) &&
                !(canRxAudit && "CRA".equals(hvacFilterMethod) && hvacAcknowledged.contains(String.format(java.util.Locale.ROOT,"ATCRA%03X",hvacTarget))))throw new SecurityException("Filter API not acknowledged");
        for(String setting:new String[]{"ATCSM1","ATCFC0","ATCAF0","ATH1","ATS1","ATD1","ATSP6","PROTOCOL_6","ATAR"})
            if(!hvacAcknowledged.contains(setting))throw new SecurityException("Missing acknowledged passive setting: "+setting);
    }
    /** Legacy raw view uses the same prompt/stop state machine as streaming capture. */
    public String listenCan(long durationMillis) throws Exception {
        synchronized(sendLock) {
            monitor(durationMillis,true,false);
            synchronized(receiveLock) {return new String(buffer.toByteArray(),StandardCharsets.US_ASCII);}
        }
    }
    /** Finite acquisition followed by bounded prompt drain; no increase to the CAN window. */
    public CanCapture.Snapshot listenCanSnapshot(long durationMillis) throws Exception {
        return listenCanSnapshot(durationMillis,false);
    }
    public CanCapture.Snapshot listenCanSnapshot(long durationMillis,boolean diagnosticTrace) throws Exception {
        synchronized(sendLock) {return monitor(durationMillis,false,diagnosticTrace,0);}
    }
    /** Only the known 0E9 control may end early after three genuine parsed frames. */
    public CanCapture.Snapshot listenCanRxControl() throws Exception {
        synchronized(sendLock) {
            if(!canRxAudit || hvacTarget!=0x0E9)throw new SecurityException("Not the fixed RX control");
            return monitor(1000,false,false,3);
        }
    }
    private CanCapture.Snapshot monitor(long durationMillis,boolean retainRaw,boolean diagnosticTrace) throws Exception {
        return monitor(durationMillis,retainRaw,diagnosticTrace,0);
    }
    private CanCapture.Snapshot monitor(long durationMillis,boolean retainRaw,boolean diagnosticTrace,int frameLimit) throws Exception {
        if(durationMillis<500 || durationMillis>(hvacOnly?30_000:5000))throw new IllegalArgumentException("Invalid monitor duration");
        if(hvacOnly)requireHvacMonitorReady();
        policy.checkAndRecord("ATMA");
        long monitorStart=System.nanoTime();
        long until=Math.min(sessionDeadline,monitorStart+TimeUnit.MILLISECONDS.toNanos(durationMillis));
        synchronized(receiveLock) {
            if(closed || waiting || monitoring)throw new IllegalStateException("Channel busy/closed");
            if(monitorStart>=sessionDeadline) {closed=true;failure="Session budget exceeded";throw new IllegalStateException(failure);}
            stream=hvacOnly?new CanCapture.Stream(monitorStart,hvacTarget):new CanCapture.Stream(monitorStart,diagnosticTrace);
            stream.current().plannedFrameLimit=frameLimit;
            buffer.reset();retainMonitorRaw=retainRaw;lastSnapshot=null;
            monitorTruncated=false;monitoring=true;stopRequested=false;
        }
        String reason, stopError=null;
        boolean stopSent=false, stopWritten=false;
        long acquisitionEnd;
        try {
            writer.write("ATMA\r".getBytes(StandardCharsets.US_ASCII));
            long startWriteDone=System.nanoTime();
            synchronized(receiveLock) {
                stream.current().startWriteConfirmed=true;
                stream.current().startWriteMillis=TimeUnit.NANOSECONDS.toMillis(startWriteDone-monitorStart);
                while(!closed && !stream.hasPrompt() && !stream.hasAdapterOverflow() && !stream.hasAdapterError()
                        && !stream.hasStopped() && !stream.isTruncated() && !stream.hasUnexpectedId()
                        && (frameLimit==0 || stream.current().frameCount<frameLimit)
                        && !stopRequested && !monitorTruncated && System.nanoTime()<until) {
                    TimeUnit.NANOSECONDS.timedWait(receiveLock,until-System.nanoTime());
                }
                if(closed)throw new IllegalStateException(failure);
                acquisitionEnd=System.nanoTime();
                reason=stream.hasAdapterOverflow()?"BUFFER_FULL":stream.hasAdapterError()?"ADAPTER_ERROR":
                        stream.hasUnexpectedId()?"FILTER_MISMATCH":stopRequested?"USER_STOP":
                        stream.hasPrompt()?"ADAPTER_PROMPT":stream.hasStopped()?"ADAPTER_STOPPED":
                        (stream.isTruncated() || monitorTruncated)?"APP_TRUNCATED":
                        frameLimit>0 && stream.current().frameCount>=frameLimit?"CONTROL_FRAME_LIMIT":
                        acquisitionEnd>=sessionDeadline?"SESSION_LIMIT":"WINDOW_ELAPSED";
                // An empty CR at an idle prompt REPEATS ATMA. Do not restart a
                // monitor that has already ended, including BUFFER FULL/error.
                stopSent=!stream.hasPrompt() && !stream.hasAdapterOverflow() && !stream.hasAdapterError()
                        && !stream.hasStopped() && acquisitionEnd<sessionDeadline;
            }
            if(stopSent) {
                // For HVAC use a single SPACE, never an idle CR. If a prompt races
                // this write, a space cannot repeat ATMA and is ignored before ATZ.
                try {writer.write((hvacOnly?" ":"\r").getBytes(StandardCharsets.US_ASCII));stopWritten=true;}
                catch(Exception e) {stopError=e.toString();}
            }
            long drainUntil=Math.min(sessionDeadline,acquisitionEnd+TimeUnit.MILLISECONDS.toNanos(STOP_DRAIN_MILLIS));
            synchronized(receiveLock) {
                while(!closed && stopError==null && !stream.hasPrompt() && System.nanoTime()<drainUntil) {
                    TimeUnit.NANOSECONDS.timedWait(receiveLock,drainUntil-System.nanoTime());
                }
                if(closed)throw new IllegalStateException(failure);
                CanCapture.Snapshot result=stream.finish();
                result.plannedFrameLimit=frameLimit;
                result.frameLimitReached=frameLimit>0 && result.frameCount>=frameLimit && "CONTROL_FRAME_LIMIT".equals(reason);
                result.startWriteConfirmed=true;
                result.captureElapsedMillis=TimeUnit.NANOSECONDS.toMillis(acquisitionEnd-monitorStart);
                result.startWriteMillis=TimeUnit.NANOSECONDS.toMillis(startWriteDone-monitorStart);
                result.lateStartWrite=startWriteDone>=monitorStart+TimeUnit.MILLISECONDS.toNanos(durationMillis);
                result.windowElapsed=acquisitionEnd>=monitorStart+TimeUnit.MILLISECONDS.toNanos(durationMillis);
                result.stopDrainMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-acquisitionEnd);
                result.endReason=reason;result.stopSent=stopSent;result.stopWriteConfirmed=stopWritten;
                result.stopError=stopError;result.stopConfirmed=result.promptSeen && stopError==null;
                result.sessionBudgetExceeded=System.nanoTime()>=sessionDeadline;
                result.truncated|=monitorTruncated;
                lastSnapshot=result;
                monitorTruncated=result.truncated;
                if(hvacOnly && result.incomplete())hvacResetOnly=true;
                if(!result.stopConfirmed || (!hvacOnly && result.truncated)) {
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
    public boolean requestMonitorStop() {
        synchronized(receiveLock) {if(!monitoring || closed)return false;stopRequested=true;receiveLock.notifyAll();return true;}
    }
    public String hvacCaptureJson() {
        synchronized(receiveLock) {return HvacJournal.captureJson(stream!=null?stream.current():lastSnapshot);}
    }
    /** Values are read from received frames only. Calling this never refreshes RX. */
    public long[] hvacProgress() {
        synchronized(receiveLock) {
            CanCapture.Snapshot s=stream!=null?stream.current():lastSnapshot;
            if(s==null)return new long[]{0,-1,-1,0};
            CanCapture.Frame f=s.frames.isEmpty()?null:s.frames.get(s.frames.size()-1);
            return new long[]{s.frameCount,f==null?-1:s.startedEpochMillis+TimeUnit.NANOSECONDS.toMillis(f.receivedNanos-s.startedNanos),
                    s.startedNanos,monitoring?1:0};
        }
    }
    public String hvacLatestDescription() {
        synchronized(receiveLock) {
            CanCapture.Snapshot s=stream!=null?stream.current():lastSnapshot;
            if(s==null || s.frames.isEmpty())return "Жодного кадру ще не отримано";
            CanCapture.Frame f=s.frames.get(s.frames.size()-1);
            if(Integer.parseInt(f.id,16)!=s.expectedId)return "ID поза фільтром: "+f.id;
            if(s.expectedId==0x0E9)return "Контрольний CAN RX 0x0E9. Це не сигнал HVAC.";
            return HvacDecoder.describe(f.id,f.payload());
        }
    }

    public void receive(byte[] bytes) {
        receive(bytes,System.nanoTime());
    }
    public void receive(byte[] bytes,long callbackEntry) {
        receive(bytes,callbackEntry,false);
    }
    public void receiveAdapterNotification(byte[] bytes,long callbackEntry) {receive(bytes,callbackEntry,true);}
    private void receive(byte[] bytes,long callbackEntry,boolean adapterInput) {
        synchronized(receiveLock) {
            if((!waiting && !monitoring) || closed) return;
            if(monitoring && stream!=null) {
                long lockWait=System.nanoTime()-callbackEntry;
                stream.accept(bytes,callbackEntry,adapterInput);
                stream.noteReceiveLockWait(lockWait);
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
        synchronized(receiveLock) {
            if(!closed && stream!=null) {
                lastSnapshot=stream.finish();lastSnapshot.endReason="CHANNEL_ABORT";
                if(hvacOnly)lastSnapshot.captureElapsedMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-lastSnapshot.startedNanos);
            }
            closed=true;waiting=false;failure=reason==null?"Closed":reason;receiveLock.notifyAll();
        }
    }
}
