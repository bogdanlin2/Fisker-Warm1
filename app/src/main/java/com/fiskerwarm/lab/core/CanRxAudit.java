package com.fiskerwarm.lab.core;

import java.util.ArrayList;
import java.util.List;

/** Fixed passive experiment. Presence evidence never turns a lossy stream into a complete capture. */
public final class CanRxAudit {
    public interface Observer {
        void phase(String name,String id,String method);
        void captured(Phase phase);
        default void recovery(String state) { }
    }
    public static final class Phase {
        public final String name,id,method;
        public final CanCapture.Snapshot capture;
        Phase(String name,String id,String method,CanCapture.Snapshot capture){this.name=name;this.id=id;this.method=method;this.capture=capture;}
        public boolean controlRxObserved(){return "0E9".equals(id) && matchingControlRxObserved(capture);}
    }
    public static final class Result {
        public final List<Phase> phases=new ArrayList<>();
        public String code;
        public int recoveriesAttempted,recoveriesCompleted;
        public boolean differentialPhasesCompleted;
        public boolean allCapturesComplete(){for(Phase p:phases)if(p.capture.incomplete())return false;return !phases.isEmpty();}
        public boolean controlStreamIncomplete(){for(Phase p:phases)if("0E9".equals(p.id) && p.capture.incomplete())return true;return false;}
        public boolean controlHasOverflow(){for(Phase p:phases)if("0E9".equals(p.id) && p.capture.adapterBufferFull)return true;return false;}
        public boolean beforeRxObserved(){return !phases.isEmpty() && phases.get(0).controlRxObserved();}
        public boolean afterRxObserved(){return differentialPhasesCompleted && !phases.isEmpty() && phases.get(phases.size()-1).controlRxObserved();}
        public boolean eccRxObserved(){for(Phase p:phases)if("373".equals(p.id) && p.capture.frameCount>0)return true;return false;}
        public boolean eccCapturesComplete(){boolean any=false;for(Phase p:phases)if("373".equals(p.id)){any=true;if(p.capture.incomplete())return false;}return any;}
    }
    private final ElmChannel channel;
    private final Observer observer;
    private final Result result=new Result();
    public CanRxAudit(ElmChannel channel,Observer observer){this.channel=channel;this.observer=observer;}
    private void ok(String cmd) throws Exception {
        if(!"OK".equals(channel.send(cmd,2500).trim()))throw new IllegalStateException("Adapter rejected "+cmd);
    }
    private Phase capture(String name,String id,String method) throws Exception {
        observer.phase(name,id,method);
        ok("ATAR");
        if("CRA".equals(method))ok("ATCRA"+id);
        else {ok("ATCM7FF");ok("ATCF"+id);}
        CanCapture.Snapshot s="0E9".equals(id)?channel.listenCanRxControl():channel.listenCanSnapshot(3000);
        Phase p=new Phase(name,id,method,s);result.phases.add(p);observer.captured(p);return p;
    }
    /** This says a valid matching adapter line was observed, not that distinct physical frames are proven. */
    public static boolean matchingControlRxObserved(CanCapture.Snapshot s) {
        if(s==null || s.expectedId!=0x0E9 || !s.startWriteConfirmed)return false;
        for(CanCapture.Frame f:s.frames)if("0E9".equals(f.id) && f.dlc==8 && f.payload().length==8)return true;
        return false;
    }
    /** Only a known adapter-overflow control prefix may cross a VERIFIED reset boundary. */
    public static boolean controlRecoveryAllowed(CanCapture.Snapshot s) {
        if(!matchingControlRxObserved(s) || !s.adapterBufferFull || s.frameCountAtAcquisitionEnd<1)return false;
        if(!s.stopConfirmed || !s.promptSeen || s.stopError!=null || (s.stopSent && !s.stopWriteConfirmed))return false;
        if(s.adapterError!=null || s.unexpectedIdOutsideFilter || s.sessionBudgetExceeded || s.lateStartWrite || s.truncated
                || s.droppedFrames!=0 || s.droppedPackets!=0 || s.nonAsciiBytes!=0 || s.overlongLines!=0)return false;
        if(!"CONTROL_FRAME_LIMIT".equals(s.endReason) && !"BUFFER_FULL".equals(s.endReason) && !"WINDOW_ELAPSED".equals(s.endReason))return false;
        if(s.frameCount!=s.frames.size() || s.notificationCount!=s.packets.size())return false;
        for(CanCapture.Frame f:s.frames)if(!"0E9".equals(f.id) || f.dlc!=8)return false;
        // Invalid/partial output during this overflow remains in raw RX and incomplete() remains true.
        return true;
    }
    private boolean usable(Phase p){return !p.capture.incomplete() && p.capture.startWriteConfirmed;}
    private boolean controlAcceptable(Phase p){return usable(p) || controlRecoveryAllowed(p.capture);}
    private void recoverBeforeNextPhase(Phase control) throws Exception {
        if(!control.capture.incomplete())return;
        if(!controlRecoveryAllowed(control.capture))throw new IllegalStateException("Unsafe control recovery");
        result.recoveriesAttempted++;observer.recovery("REQUIRED_AFTER_LOSSY_CONTROL");
        String banner=channel.send("ATZ",4000).trim();
        if(!banner.matches("ELM327 v[0-9]+\\.[0-9]+"))throw new IllegalStateException("Reset banner malformed or contaminated");
        observer.recovery("RESET_BANNER_ACKNOWLEDGED");
        ok("ATE0");
        if(!banner.equals(channel.send("ATI",2500).trim()))throw new IllegalStateException("Adapter identity changed during recovery");
        Double voltage=ElmParser.voltage(channel.send("ATRV",2500));
        if(!ReadOnlyPolicy.diagnosticVoltageAcceptable(voltage))throw new IllegalStateException("Recovery voltage unknown or unsafe");
        for(String cmd:new String[]{"ATL0","ATS1","ATH1","ATSP6","ATCAF0","ATCFC0","ATCSM1","ATD1"})ok(cmd);
        if(!"6".equals(channel.send("ATDPN",2500).trim()))throw new IllegalStateException("Protocol 6 not reacknowledged");
        result.recoveriesCompleted++;observer.recovery("ALL_PASSIVE_SETTINGS_REACKNOWLEDGED");
    }
    private String withControlLoss(String code){return result.controlStreamIncomplete()?code+"_WITH_CONTROL_LOSS":code;}
    public Result run() throws Exception {
        Phase pre=capture("CONTROL_BEFORE","0E9","CM_CF");
        if(!controlAcceptable(pre)){result.code="CONTROL_CAPTURE_INCOMPLETE";return result;}
        if(pre.capture.frameCount==0) {
            Phase alternate=capture("CONTROL_ALTERNATE_FILTER","0E9","CRA");
            if(!controlAcceptable(alternate)){result.code="CONTROL_CAPTURE_INCOMPLETE";return result;}
            if(alternate.capture.frameCount==0){result.code="NO_CONTROL_RX";return result;}
            recoverBeforeNextPhase(alternate);
            Phase repeat=capture("CONTROL_CM_CF_RECHECK","0E9","CM_CF");
            result.code=!controlAcceptable(repeat)?"CONTROL_CAPTURE_INCOMPLETE":withControlLoss(repeat.capture.frameCount==0?
                    "FILTER_API_DIFFERENCE_OBSERVED":"CONTROL_RX_INTERMITTENT");
            return result;
        }
        recoverBeforeNextPhase(pre);
        Phase ecc=capture("ECC_VISIBILITY","373","CM_CF");
        if(!usable(ecc)){result.code="ECC_CAPTURE_INCOMPLETE";return result;}
        Phase eccAlternate=null,eccRepeat=null;
        if(ecc.capture.frameCount==0) {
            eccAlternate=capture("ECC_ALTERNATE_FILTER","373","CRA");
            if(!usable(eccAlternate)){result.code="ECC_CAPTURE_INCOMPLETE";return result;}
            if(eccAlternate.capture.frameCount>0) {
                eccRepeat=capture("ECC_CM_CF_RECHECK","373","CM_CF");
                if(!usable(eccRepeat)){result.code="ECC_CAPTURE_INCOMPLETE";return result;}
            }
        }
        Phase post=capture("CONTROL_AFTER","0E9","CM_CF");
        if(!controlAcceptable(post)){result.code="CONTROL_CAPTURE_INCOMPLETE";return result;}
        result.differentialPhasesCompleted=true;
        result.code=post.capture.frameCount==0?"CONTROL_AFTER_MISSING":withControlLoss(
                eccAlternate!=null && eccAlternate.capture.frameCount>0?
                        (eccRepeat.capture.frameCount==0?"ECC_RX_ONLY_VIA_CRA_OBSERVED":"ECC_RX_INTERMITTENT"):
                ecc.capture.frameCount>0?"ECC_RX_OBSERVED_WITH_CONTROLS":"CONTROL_RX_PRESENT_ECC_NOT_OBSERVED");
        return result;
    }
}
