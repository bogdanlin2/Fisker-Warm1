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

/** Bounded, deliberately undecoded representation of passive 11-bit CAN traffic. */
public final class CanCapture {
    private static final Pattern HEADER = Pattern.compile("[0-7][0-9A-F]{2}");
    private static final Pattern BYTE = Pattern.compile("[0-9A-F]{2}");
    public static final int MAX_CAPTURE_LINES = 1500;
    public static final int MAX_SAMPLES_PER_ID = 12;

    public static final class IdSummary {
        public final String id;
        public int count;
        public final Set<String> samples = new LinkedHashSet<>();
        private IdSummary(String id) { this.id=id; }
    }
    public static final class Snapshot {
        public int frameCount;
        public int ignoredLines;
        public boolean truncated;
        public boolean adapterBufferFull;
        public int receivedBytes, notificationCount, framesAtFirstOverflow = -1;
        public long captureElapsedMillis;
        public final Map<String,IdSummary> ids=new LinkedHashMap<>();
    }
    private CanCapture() { }

    public static Snapshot parse(String response) {
        Snapshot result=new Snapshot();
        if(response == null) return result;
        String[] lines=response.replace('\r','\n').split("\n");
        for(int i=0;i<lines.length;i++) {
            if(i>=MAX_CAPTURE_LINES) { result.truncated=true; break; }
            String line=lines[i].trim().toUpperCase(Locale.ROOT);
            if(line.contains("BUFFER FULL")) { result.adapterBufferFull=true; continue; }
            if(line.isEmpty() || line.startsWith("ATMA") || line.equals("STOPPED") || line.equals(">") || line.equals("SEARCHING...")) continue;
            String[] p=line.split("\\s+");
            if(p.length<2 || !HEADER.matcher(p[0]).matches()) { result.ignoredLines++;continue; }
            int start=1;
            if(p[1].length()==1 && p[1].matches("[0-8]")) start=2; // optional ELM DLC
            if(start>=p.length || p.length-start>8) { result.ignoredLines++;continue; }
            boolean valid=true;
            for(int j=start;j<p.length;j++) if(!BYTE.matcher(p[j]).matches()) valid=false;
            if(!valid){result.ignoredLines++;continue;}
            StringBuilder bytes=new StringBuilder();
            for(int j=start;j<p.length;j++) {if(j>start)bytes.append(' ');bytes.append(p[j]);}
            IdSummary id=result.ids.get(p[0]);
            if(id==null) {id=new IdSummary(p[0]); result.ids.put(p[0],id);}
            id.count++;result.frameCount++;
            if(id.samples.size()<MAX_SAMPLES_PER_ID)id.samples.add(bytes.toString());
        }
        return result;
    }
    /** Incremental bounded monitor parser: never stores the full four-second stream. */
    public static final class Stream {
        private final Snapshot snapshot=new Snapshot();
        private final StringBuilder line=new StringBuilder();
        private int lines;
        public void accept(byte[] bytes) {
            snapshot.notificationCount++;
            snapshot.receivedBytes+=bytes.length;
            for(byte b:bytes) {
                int c=b&255;
                if(c==13 || c==10) { flush(); continue; }
                if(c>127) { snapshot.truncated=true; continue; }
                if(line.length()>=160) { snapshot.truncated=true; continue; }
                line.append((char)c);
            }
        }
        private void flush() {
            if(line.length()==0)return;
            String text=line.toString();line.setLength(0);
            if(lines++>=MAX_CAPTURE_LINES) {snapshot.truncated=true;return;}
            Snapshot one=parse(text);
            snapshot.ignoredLines+=one.ignoredLines;
            if(one.adapterBufferFull && !snapshot.adapterBufferFull) snapshot.framesAtFirstOverflow=snapshot.frameCount;
            snapshot.adapterBufferFull|=one.adapterBufferFull;
            for(IdSummary item:one.ids.values()) {
                IdSummary target=snapshot.ids.get(item.id);
                if(target==null) {target=new IdSummary(item.id);snapshot.ids.put(item.id,target);}
                target.count+=item.count;snapshot.frameCount+=item.count;
                for(String sample:item.samples) if(target.samples.size()<MAX_SAMPLES_PER_ID)target.samples.add(sample);
            }
        }
        public boolean hasAdapterOverflow() { return snapshot.adapterBufferFull || line.toString().toUpperCase(Locale.ROOT).contains("BUFFER FULL"); }
        public boolean isTruncated() { return snapshot.truncated; }
        public Snapshot finish() {flush();return snapshot;}
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
