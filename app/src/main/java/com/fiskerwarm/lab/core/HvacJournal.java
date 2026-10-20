package com.fiskerwarm.lab.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Full bounded journal serializer. No Android, simulated RX, cache heartbeat or command encoder. */
public final class HvacJournal {
    private final List<String> marks=new ArrayList<>();
    public synchronized void mark(String kind,long nanos,long epochMillis) {
        if(!"HMI_ON".equals(kind) && !"HMI_OFF".equals(kind) && !"AIRFLOW_PRESENT".equals(kind) && !"WARM_AIR_PRESENT".equals(kind))
            throw new IllegalArgumentException("Unknown owner mark");
        if(marks.size()>=100)throw new IllegalStateException("Owner mark storage limit");
        marks.add("{\"kind\":"+quote(kind)+",\"source\":\"OWNER_REPORTED\",\"receivedNanos\":"+nanos+",\"utcEpochMillis\":"+epochMillis+"}");
    }
    public synchronized String marksJson() {return "["+String.join(",",marks)+"]";}
    public static String quote(String text) {
        if(text==null)return "null";
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"' || c=='\\')out.append('\\').append(c);
            else if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    public static String captureJson(CanCapture.Snapshot s) {
        if(s==null)return "null";
        StringBuilder j=new StringBuilder("{");
        j.append("\"frameCount\":").append(s.frameCount).append(",\"retainedFrames\":").append(s.frames.size());
        j.append(",\"expectedId\":").append(quote(String.format(java.util.Locale.ROOT,"%03X",s.expectedId)));
        j.append(",\"rxTimeMeaning\":\"Host BLE callback completing a CR-terminated frame; not a CAN-bus hardware timestamp\"");
        j.append(",\"startedEpochMillis\":").append(s.startedEpochMillis).append(",\"startedNanos\":").append(s.startedNanos);
        j.append(",\"receivedBytes\":").append(s.receivedBytes).append(",\"notificationCount\":").append(s.notificationCount);
        j.append(",\"maxNotificationGapMillis\":").append(s.maxNotificationGapMillis);
        j.append(",\"notificationLossIndependentlyDetectable\":false,\"physicalAdapterSilentModeVerified\":false");
        j.append(",\"completenessScope\":\"HOST_PARSER_ONLY; complete-frame losses before BLE callback are not observable\"");
        j.append(",\"containsSyntheticInput\":").append(s.containsSyntheticInput);
        j.append(",\"captureElapsedMillis\":").append(s.captureElapsedMillis).append(",\"windowElapsed\":").append(s.windowElapsed);
        j.append(",\"captureElapsedMeaning\":\"Until host stop decision; excludes stop drain\",\"frameCountScope\":\"Includes stop drain\"");
        j.append(",\"frameCountAtAcquisitionEnd\":").append(s.frameCountAtAcquisitionEnd);
        j.append(",\"notificationCountAtAcquisitionEnd\":").append(s.notificationCountAtAcquisitionEnd);
        j.append(",\"bytesAtAcquisitionEnd\":").append(s.bytesAtAcquisitionEnd);
        j.append(",\"drainFrameCount\":").append(s.frameCountAtAcquisitionEnd<0?-1:s.frameCount-s.frameCountAtAcquisitionEnd);
        j.append(",\"acquisitionEndedNanos\":").append(s.acquisitionEndedNanos).append(",\"recordingEndedNanos\":").append(s.recordingEndedNanos);
        j.append(",\"captureTotalMillis\":").append(s.captureTotalMillis);
        j.append(",\"frameLimitReachedOnRxNanos\":").append(s.frameLimitReachedOnRxNanos);
        j.append(",\"frameLimitReachedParsedNanos\":").append(s.frameLimitReachedParsedNanos);
        j.append(",\"limitMeaning\":\"Host parsed-frame stop trigger, not an adapter or physical-CAN frame quota\"");
        j.append(",\"maxProcessingMicros\":").append(s.maxProcessingMicros).append(",\"maxReceiveLockWaitMicros\":").append(s.maxReceiveLockWaitMicros);
        j.append(",\"framesAtFirstOverflow\":").append(s.framesAtFirstOverflow).append(",\"firstOverflowMillis\":").append(s.firstOverflowMillis);
        j.append(",\"invalidLineSamples\":[");
        for(int i=0;i<s.invalidLineSamples.size();i++){if(i>0)j.append(',');j.append(quote(s.invalidLineSamples.get(i)));}
        j.append(']');
        j.append(",\"startWriteMillis\":").append(s.startWriteMillis).append(",\"startWriteConfirmed\":").append(s.startWriteConfirmed);
        j.append(",\"startWriteEvidence\":\"Host GATT completion only; not proof of ELM interpretation or CAN RX\"");
        j.append(",\"plannedFrameLimit\":").append(s.plannedFrameLimit).append(",\"frameLimitReached\":").append(s.frameLimitReached);
        j.append(",\"inProgress\":").append(s.endReason==null).append(",\"incomplete\":").append(s.incomplete());
        j.append(",\"endReason\":").append(quote(s.endReason)).append(",\"adapterError\":").append(quote(s.adapterError));
        j.append(",\"stopError\":").append(quote(s.stopError)).append(",\"adapterBufferFull\":").append(s.adapterBufferFull);
        j.append(",\"stopSent\":").append(s.stopSent).append(",\"stopWriteConfirmed\":").append(s.stopWriteConfirmed);
        j.append(",\"stopConfirmed\":").append(s.stopConfirmed).append(",\"promptSeen\":").append(s.promptSeen);
        j.append(",\"truncated\":").append(s.truncated).append(",\"invalidFrameLines\":").append(s.invalidFrameLines);
        j.append(",\"partialLinesDiscarded\":").append(s.partialLinesDiscarded).append(",\"nonAsciiBytes\":").append(s.nonAsciiBytes);
        j.append(",\"ignoredNullBytes\":").append(s.ignoredNullBytes).append(",\"overlongLines\":").append(s.overlongLines);
        j.append(",\"unexpectedIdOutsideFilter\":").append(s.unexpectedIdOutsideFilter);
        j.append(",\"droppedFrames\":").append(s.droppedFrames).append(",\"droppedPackets\":").append(s.droppedPackets);
        j.append(",\"sessionBudgetExceeded\":").append(s.sessionBudgetExceeded).append(",\"lateStartWrite\":").append(s.lateStartWrite);
        j.append(",\"stopDrainMillis\":").append(s.stopDrainMillis).append(",\"frames\":[");
        boolean first=true;
        for(CanCapture.Frame f:s.frames) {
            if(!first)j.append(',');first=false;
            long us=TimeUnit.NANOSECONDS.toMicros(f.receivedNanos-s.startedNanos);
            j.append("{\"id\":").append(quote(f.id)).append(",\"dlc\":").append(f.dlc).append(",\"payload\":").append(quote(f.payloadHex));
            j.append(",\"receivedNanos\":").append(f.receivedNanos).append(",\"relativeMicros\":").append(us);
            j.append(",\"utcEpochMillis\":").append(s.startedEpochMillis+us/1000).append(",\"notificationNumber\":").append(f.notificationNumber);
            j.append(",\"source\":").append(quote(f.source)).append(",\"matrixCompatibilityWithVehicle\":\"UNVERIFIED\"");
            j.append(",\"bleState\":").append(quote(f.source.equals("SYNTHETIC_TEST")?"NOT_APPLICABLE_SYNTHETIC":"CONNECTED_AT_NOTIFICATION"));
            j.append(",\"adapterState\":\"ATMA_RECEIVE\"");
            if(Integer.parseInt(f.id,16)==s.expectedId && ("534".equals(f.id) || "373".equals(f.id))) {
                int[] d=HvacDecoder.decode(f.id,f.payload());
                j.append(",\"decoded\":{");
                if("534".equals(f.id))j.append("\"ICC_ECCSysSwtCmd\":").append(d[0]);
                else j.append("\"ECC_CrtSts\":").append(d[0]).append(",\"ECC_WindSpdSts\":").append(d[1])
                        .append(",\"ECC_HeatSts\":").append(d[2]).append(",\"ECC_RemSts\":").append(d[3]);
                j.append('}');
            }
            j.append('}');
        }
        j.append("],\"notifications\":[");first=true;
        for(CanCapture.Packet p:s.packets) {
            if(!first)j.append(',');first=false;
            j.append("{\"receivedNanos\":").append(p.receivedNanos).append(",\"hex\":").append(quote(p.hex)).append('}');
        }
        return j.append("]}").toString();
    }
}
