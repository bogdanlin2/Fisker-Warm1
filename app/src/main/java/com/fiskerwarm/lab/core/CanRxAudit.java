package com.fiskerwarm.lab.core;

import java.util.ArrayList;
import java.util.List;

/** A single bounded differential experiment. All commands configure or read the adapter. */
public final class CanRxAudit {
    public interface Observer {void phase(String name,String id,String method);void captured(Phase phase);}
    public static final class Phase {
        public final String name,id,method;
        public final CanCapture.Snapshot capture;
        Phase(String name,String id,String method,CanCapture.Snapshot capture){this.name=name;this.id=id;this.method=method;this.capture=capture;}
    }
    public static final class Result {
        public final List<Phase> phases=new ArrayList<>();
        public String code;
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
    private boolean usable(Phase p){return !p.capture.incomplete() && p.capture.startWriteConfirmed;}
    public Result run() throws Exception {
        Phase pre=capture("CONTROL_BEFORE","0E9","CM_CF");
        if(!usable(pre)){result.code="CONTROL_CAPTURE_INCOMPLETE";return result;}
        if(pre.capture.frameCount==0) {
            Phase alternate=capture("CONTROL_ALTERNATE_FILTER","0E9","CRA");
            if(!usable(alternate)){result.code="CONTROL_CAPTURE_INCOMPLETE";return result;}
            if(alternate.capture.frameCount==0){result.code="NO_CONTROL_RX";return result;}
            Phase repeat=capture("CONTROL_CM_CF_RECHECK","0E9","CM_CF");
            result.code=!usable(repeat)?"CONTROL_CAPTURE_INCOMPLETE":repeat.capture.frameCount==0?
                    "FILTER_API_DIFFERENCE_OBSERVED":"CONTROL_RX_INTERMITTENT";
            return result;
        }
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
        result.code=!usable(post)?"CONTROL_CAPTURE_INCOMPLETE":post.capture.frameCount==0?"CONTROL_AFTER_MISSING":
                eccAlternate!=null && eccAlternate.capture.frameCount>0?
                        (eccRepeat.capture.frameCount==0?"ECC_RX_ONLY_VIA_CRA_OBSERVED":"ECC_RX_INTERMITTENT"):
                ecc.capture.frameCount>0?"ECC_RX_OBSERVED_WITH_CONTROLS":"CONTROL_RX_PRESENT_ECC_NOT_OBSERVED";
        return result;
    }
}
