package com.fiskerwarm.lab.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/** Bounded, deliberately undecoded representation of passive 11-bit CAN traffic. */
public final class CanCapture {
    private static final Pattern HEADER = Pattern.compile("[0-7][0-9A-F]{2}");
    private static final Pattern BYTE = Pattern.compile("[0-9A-F]{2}");
    public static final int MAX_CAPTURE_LINES = 1500;
    public static final int MAX_SAMPLES_PER_ID = 12;
    public static final int MAX_RAW_TRACE_BYTES=2048, MAX_NOTIFICATION_TRACE=64, MAX_INVALID_LINE_SAMPLES=3;
    public static final class NotificationTrace {
        public final int bytes;
        public final long arrivalMicros,processingMicros;
        private NotificationTrace(int bytes,long arrivalMicros,long processingMicros) {
            this.bytes=bytes;this.arrivalMicros=arrivalMicros;this.processingMicros=processingMicros;
        }
    }

    public static final class IdSummary {
        public final String id;
        public int count;
        public final Set<String> samples = new LinkedHashSet<>();
        private IdSummary(String id) { this.id=id; }
    }
    public static final class Snapshot {
        public int frameCount;
        public int framesWithDlc, framesWithoutDlc;
        public int ignoredLines;
        public boolean truncated;
        public boolean adapterBufferFull;
        public int receivedBytes, notificationCount, framesAtFirstOverflow = -1;
        public int bytesAtFirstOverflow = -1, minNotificationBytes, maxNotificationBytes;
        public int partialLinesDiscarded, invalidFrameLines, overlongLines, nonAsciiBytes, ignoredNullBytes;
        public long captureElapsedMillis, stopDrainMillis, startWriteMillis, firstNotificationMillis = -1;
        public long lastNotificationMillis = -1, firstOverflowMillis = -1, maxNotificationGapMillis;
        public boolean promptSeen, adapterStopped, stopSent, stopWriteConfirmed, stopConfirmed, sessionBudgetExceeded;
        public boolean windowElapsed, lateStartWrite;
        public String rawAsciiPrefix;
        public boolean rawTraceTruncated, notificationTraceTruncated;
        public long maxProcessingMicros, maxReceiveLockWaitMicros;
        public final List<NotificationTrace> notificationTrace=new ArrayList<>();
        public final List<String> invalidLineSamples=new ArrayList<>();
        public String adapterError, endReason, stopError;
        public final Map<String,IdSummary> ids=new LinkedHashMap<>();
        public boolean incomplete() {
            return adapterBufferFull || truncated || invalidFrameLines>0 || adapterError!=null
                    || sessionBudgetExceeded || !stopConfirmed || !windowElapsed || lateStartWrite;
        }
    }
    private CanCapture() { }

    public static Snapshot parse(String response) {
        Snapshot result=new Snapshot();
        if(response == null) return result;
        int count=0;
        for(String text:response.replace("\u0000", "").replace('>','\n').replace('\r','\n').split("\n")) {
            String line=text.trim().toUpperCase(Locale.ROOT);
            if(line.isEmpty())continue;
            // Status is still important after the application storage limit.
            if(status(line,result))continue;
            if(count++>=MAX_CAPTURE_LINES) {result.truncated=true;continue;}
            parseFrame(line,result);
        }
        return result;
    }
    private static boolean status(String line,Snapshot s) {
        if(line.contains("BUFFER FULL")) {s.adapterBufferFull=true;return true;}
        if(line.equals("STOPPED")) {s.adapterStopped=true;return true;}
        if(line.equals("ATMA") || line.equals("SEARCHING..."))return true;
        for(String error:new String[]{"CAN ERROR","BUS ERROR","BUS BUSY","RX ERROR","<RX ERROR","NO DATA","UNABLE TO CONNECT","?"}) {
            if(line.equals(error)) {s.adapterError=error;return true;}
        }
        return false;
    }
    private static void invalid(Snapshot s,String line) {
        s.ignoredLines++;s.invalidFrameLines++;
        if(s.invalidLineSamples.size()<MAX_INVALID_LINE_SAMPLES)s.invalidLineSamples.add(line.substring(0,Math.min(160,line.length())));
    }
    private static void parseFrame(String line,Snapshot s) {
        String[] p=line.split("\\s+");
        if(p.length<2 || !HEADER.matcher(p[0]).matches()) {invalid(s,line);return;}
        int start=1, dlc=-1;
        if(p[1].length()==1 && p[1].matches("[0-8]")) {dlc=p[1].charAt(0)-'0';start=2;}
        int length=p.length-start;
        if(length>8 || (dlc<0 && length==0) || (dlc>=0 && dlc!=length)) {invalid(s,line);return;}
        for(int j=start;j<p.length;j++) if(!BYTE.matcher(p[j]).matches()) {invalid(s,line);return;}
        StringBuilder bytes=new StringBuilder();
        for(int j=start;j<p.length;j++) {if(j>start)bytes.append(' ');bytes.append(p[j]);}
        IdSummary id=s.ids.get(p[0]);
        if(id==null) {id=new IdSummary(p[0]);s.ids.put(p[0],id);}
        id.count++;s.frameCount++;
        if(dlc>=0)s.framesWithDlc++;else s.framesWithoutDlc++;
        if(id.samples.size()<MAX_SAMPLES_PER_ID)id.samples.add(bytes.toString());
    }
    /** Incremental bounded monitor parser: never stores the full monitor stream. */
    public static final class Stream {
        private final Snapshot snapshot=new Snapshot();
        private final StringBuilder line=new StringBuilder();
        private final long startedNanos;
        private int lines;
        private boolean discardLine, finished;
        private final StringBuilder rawTrace;
        public Stream() {this(System.nanoTime());}
        public Stream(long startedNanos) {this(startedNanos,false);}
        public Stream(long startedNanos,boolean diagnosticTrace) {this.startedNanos=startedNanos;rawTrace=diagnosticTrace?new StringBuilder():null;}
        public void accept(byte[] bytes) {
            accept(bytes,System.nanoTime());
        }
        public void accept(byte[] bytes,long receivedNanos) {
            if(finished)throw new IllegalStateException("Capture already finished");
            if(bytes==null || bytes.length==0)return;
            long processingStart=System.nanoTime();
            long elapsed=Math.max(0,TimeUnit.NANOSECONDS.toMillis(receivedNanos-startedNanos));
            snapshot.notificationCount++;
            snapshot.receivedBytes+=bytes.length;
            snapshot.minNotificationBytes=snapshot.notificationCount==1?bytes.length:Math.min(snapshot.minNotificationBytes,bytes.length);
            snapshot.maxNotificationBytes=Math.max(snapshot.maxNotificationBytes,bytes.length);
            if(snapshot.firstNotificationMillis<0)snapshot.firstNotificationMillis=elapsed;
            if(snapshot.lastNotificationMillis>=0)snapshot.maxNotificationGapMillis=Math.max(snapshot.maxNotificationGapMillis,elapsed-snapshot.lastNotificationMillis);
            snapshot.lastNotificationMillis=elapsed;
            for(byte b:bytes) {
                int c=b&255;
                if(rawTrace!=null) {
                    if(rawTrace.length()<MAX_RAW_TRACE_BYTES)rawTrace.append(c<128?(char)c:'?');
                    else snapshot.rawTraceTruncated=true;
                }
                // ELM327 datasheet documents rare inserted NULLs; ignore only NULL.
                if(c==0) {snapshot.ignoredNullBytes++;continue;}
                if(snapshot.promptSeen) {
                    if(c!=13 && c!=10 && c!=' ')snapshot.truncated=true;
                    continue;
                }
                if(c==13 || c==10) {flush(elapsed);continue;}
                if(c=='>') {
                    // A prompt is a control delimiter, never a CAN line terminator.
                    discardPartial(elapsed);snapshot.promptSeen=true;continue;
                }
                if(c>127 || (c<32 && c!=9)) {
                    snapshot.nonAsciiBytes++;snapshot.truncated=true;discardLine=true;continue;
                }
                if(discardLine)continue;
                if(line.length()>=160) {
                    snapshot.overlongLines++;snapshot.truncated=true;discardLine=true;continue;
                }
                line.append((char)c);
            }
            // Some adapters omit CR after BUFFER FULL; observe it before finish().
            noteOverflow(line.toString().toUpperCase(Locale.ROOT),elapsed);
            long processingMicros=TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-processingStart);
            snapshot.maxProcessingMicros=Math.max(snapshot.maxProcessingMicros,processingMicros);
            if(rawTrace!=null) {
                if(snapshot.notificationTrace.size()<MAX_NOTIFICATION_TRACE)
                    snapshot.notificationTrace.add(new NotificationTrace(bytes.length,Math.max(0,TimeUnit.NANOSECONDS.toMicros(receivedNanos-startedNanos)),processingMicros));
                else snapshot.notificationTraceTruncated=true;
            }
        }
        public void noteReceiveLockWait(long waitNanos) {
            snapshot.maxReceiveLockWaitMicros=Math.max(snapshot.maxReceiveLockWaitMicros,Math.max(0,TimeUnit.NANOSECONDS.toMicros(waitNanos)));
        }
        private void noteOverflow(String text,long elapsed) {
            if(text.contains("BUFFER FULL") && !snapshot.adapterBufferFull) {
                snapshot.adapterBufferFull=true;snapshot.framesAtFirstOverflow=snapshot.frameCount;
                // BLE gives packet timestamps, not individual byte timestamps.
                snapshot.bytesAtFirstOverflow=snapshot.receivedBytes;snapshot.firstOverflowMillis=elapsed;
                if(text.indexOf("BUFFER FULL")>0) {snapshot.partialLinesDiscarded++;snapshot.truncated=true;}
            }
        }
        private void flush(long elapsed) {
            String text=line.toString().trim().toUpperCase(Locale.ROOT);line.setLength(0);
            if(discardLine) {snapshot.ignoredLines++;discardLine=false;return;}
            if(text.isEmpty())return;
            noteOverflow(text,elapsed);
            if(status(text,snapshot))return;
            if(lines++>=MAX_CAPTURE_LINES) {snapshot.truncated=true;return;}
            parseFrame(text,snapshot);
        }
        private void discardPartial(long elapsed) {
            String text=line.toString().trim().toUpperCase(Locale.ROOT);
            noteOverflow(text,elapsed);
            if(!text.isEmpty() || discardLine) {
                // Status tokens do not need a trailing newline; payloads do.
                if(discardLine || !status(text,snapshot)) {
                    snapshot.partialLinesDiscarded++;snapshot.truncated=true;
                }
            }
            line.setLength(0);discardLine=false;
        }
        public boolean hasAdapterOverflow() {return snapshot.adapterBufferFull;}
        public boolean hasPrompt() {return snapshot.promptSeen;}
        public boolean hasAdapterError() {return snapshot.adapterError!=null;}
        public boolean hasStopped() {return snapshot.adapterStopped;}
        public boolean isTruncated() { return snapshot.truncated; }
        public Snapshot finish() {
            if(!finished) {discardPartial(snapshot.lastNotificationMillis);finished=true;if(rawTrace!=null)snapshot.rawAsciiPrefix=rawTrace.toString();}
            return snapshot;
        }
    }
    /** IDs may be noisy, and a difference is NOT a decoded climate signal. */
    public static List<String> changedIds(Snapshot before,Snapshot after) {
        if(before==null || after==null) return Collections.emptyList();
        Set<String> all=new LinkedHashSet<>(before.ids.keySet());all.addAll(after.ids.keySet());
        List<String> changed=new ArrayList<>();
        for(String id:all) {
            IdSummary a=before.ids.get(id),b=after.ids.get(id);
            if(a==null || b==null || !a.samples.equals(b.samples) || Math.abs(a.count-b.count)>5)changed.add(id);
        }
        return changed;
    }
}
