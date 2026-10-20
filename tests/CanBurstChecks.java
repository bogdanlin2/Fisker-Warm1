import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Recorded owner bytes replayed through SYNTHETIC transports. Never real CAN RX or a vehicle test. */
public final class CanBurstChecks {
    interface Check {void run()throws Exception;}
    static int checks;
    static void yes(boolean v){if(!v)throw new AssertionError("Condition failed");}
    static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void test(String n,Check c)throws Exception{c.run();checks++;System.out.println("PASS "+n);}
    static void fails(Check c)throws Exception{try{c.run();}catch(IllegalStateException|SecurityException e){return;}throw new AssertionError("Expected fail closed");}
    static byte[] ascii(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    static final long ORIGINAL_START=71409250773330L,ORIGINAL_STOP=71409304651351L,ORIGINAL_MA=71409251963799L;
    static final List<Long> TIMES=new ArrayList<>();static final List<byte[]> PACKETS=new ArrayList<>();
    static void fixture()throws Exception{
        Path p=Paths.get(System.getProperty("fiskerwarm.repoRoot","."),"verification/stage7-5/recorded-burst.tsv");
        for(String line:Files.readAllLines(p,StandardCharsets.UTF_8))if(!line.startsWith("#") && !line.trim().isEmpty()){
            String[] a=line.split("\t");TIMES.add(Long.parseLong(a[0]));PACKETS.add(hex(a[1]));}
        eq(7,PACKETS.size());
    }
    static CanCapture.Snapshot recorded(){
        CanCapture.Stream s=new CanCapture.Stream(ORIGINAL_START,0x0E9);s.current().plannedFrameLimit=3;
        for(int i=0;i<PACKETS.size();i++){
            s.accept(PACKETS.get(i),TIMES.get(i));
            if(i==0)s.current().frameCountAtAcquisitionEnd=s.current().frameCount; // Synthetic boundary annotation; original exact CPU time was not logged.
        }
        CanCapture.Snapshot c=s.finish();c.startWriteConfirmed=true;c.stopSent=true;c.stopWriteConfirmed=true;c.stopConfirmed=c.promptSeen;
        c.endReason="CONTROL_FRAME_LIMIT";c.frameLimitReached=true;return c;
    }
    static class Replay implements AutoCloseable {
        final ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();
        final List<String> writes=new CopyOnWriteArrayList<>();final List<String> recoveries=new CopyOnWriteArrayList<>();
        final BleIoJournal io=new BleIoJournal();
        final GattWriteGate gate=new GattWriteGate((k,i,b,s)->io.add(k,System.nanoTime(),i,b,s,"2AF1","SYNTHETIC_GATT"));
        final ElmChannel ch=ElmChannel.forCanRxAudit(this::write,25000);
        String target="",badRecovery="";int resets;boolean eccFrame,eccOverflow,noPrompt;
        void rx(byte[] b){io.add("RX_NOTIFICATION",System.nanoTime(),0,b,-1,"2AF0","SYNTHETIC_REPLAY");ch.receive(b);}
        void later(long nanos,Runnable r){producer.schedule(r,Math.max(0,nanos),TimeUnit.NANOSECONDS);}
        void write(byte[] value)throws Exception{
            yes(PassiveNetworkPlan.hvacWireBytesAllowed(value,true));
            gate.write(value,1000,b->{
                String s=new String(b,StandardCharsets.US_ASCII);writes.add(s);
                if(s.equals("ATMA\r")){
                    if(target.equals("0E9"))later(TIMES.get(0)-ORIGINAL_MA,()->rx(PACKETS.get(0)));
                    else if(eccFrame)later(40_000_000L,()->rx(ascii(CanRxChecks.ECC)));
                    later(25_680_833L,()->gate.complete(0));return 0;
                }
                if(s.equals(" ")){
                    if(target.equals("0E9")){
                        for(int i=1;i<PACKETS.size();i++){
                            final int n=i;
                            later(TIMES.get(i)-ORIGINAL_STOP,()->{
                                byte[] packet=PACKETS.get(n);
                                if(noPrompt && n==PACKETS.size()-1){String a=new String(packet,StandardCharsets.US_ASCII).replace(">","");packet=ascii(a);}
                                rx(packet);
                            });
                        }
                        later(50_924_531L,()->gate.complete(0));
                    } else {
                        later(1_000_000L,()->{rx(ascii(eccOverflow?"BUFFER FULL\rSTOPPED\r\r>":"STOPPED\r\r>"));gate.complete(0);});
                    }
                    return 0;
                }
                String cmd=s.trim();
                if(cmd.equals("ATZ"))resets++;
                if(cmd.startsWith("ATCF"))target=cmd.substring(4);
                if(cmd.startsWith("ATCRA"))target=cmd.substring(5);
                final String reply;
                boolean recovery=resets>1;
                if(recovery && badRecovery.equals(cmd))reply=cmd.equals("ATZ")?"0E9 8 00 00\rELM327 v2.2\r>":
                        cmd.equals("ATI")?"ELM327 v2.1\r>":cmd.equals("ATRV")?"11.0V\r>":cmd.equals("ATDPN")?"7\r>":"?\r>";
                else reply=cmd.equals("ATZ")||cmd.equals("ATI")?"ELM327 v2.2\r>":cmd.equals("ATRV")?"14.0V\r>":cmd.equals("ATDPN")?"6\r>":"OK\r>";
                later(1_000_000L,()->{rx(ascii(reply));gate.complete(0);});return 0;
            });
        }
        void setup()throws Exception{for(String s:CanRxChecks.SETUP)ch.send(s,500);}
        void arm()throws Exception{ch.send("ATAR",500);ch.send("ATCM7FF",500);ch.send("ATCF0E9",500);}
        CanRxAudit.Result audit()throws Exception{return new CanRxAudit(ch,new CanRxAudit.Observer(){
            public void phase(String n,String i,String m){}
            public void captured(CanRxAudit.Phase p){yes(p.capture.containsSyntheticInput);HvacJournal.captureJson(p.capture);}
            public void recovery(String state){recoveries.add(state);}
        }).run();}
        public void close(){ch.close("Replay complete");gate.close();producer.shutdownNow();}
    }
    public static void main(String[] args)throws Exception{
        fixture();
        test("all 1305 original raw bytes parse to 40 valid identical lines and two malformed lines",()->{
            CanCapture.Snapshot c=recorded();eq(40,c.frameCount);eq(7,c.notificationCount);eq(1305,c.receivedBytes);eq(2,c.invalidFrameLines);
            yes(c.adapterBufferFull && c.adapterStopped && c.promptSeen && c.incomplete() && c.containsSyntheticInput);
            Set<String> p=new HashSet<>();for(CanCapture.Frame f:c.frames){eq("0E9",f.id);eq("SYNTHETIC_TEST",f.source);p.add(f.payloadHex);}eq(1,p.size());
            for(int i=0;i<7;i++)eq(ElmParser.hexText(PACKETS.get(i)),c.packets.get(i).hex);
            eq(40,c.framesAtFirstOverflow);eq(149L,c.firstOverflowMillis);
        });
        test("the first 200-byte callback has six frames; threshold timestamps refer to that callback",()->{
            CanCapture.Snapshot c=recorded();eq(6,c.frameCountAtAcquisitionEnd);eq(TIMES.get(0),c.frameLimitReachedOnRxNanos);
            eq(34,c.frameCount-c.frameCountAtAcquisitionEnd);yes(c.frameLimitReachedParsedNanos>0);
        });
        test("recorded async order preserves tail after SPACE and keeps overflow incomplete",()->{
            try(Replay r=new Replay()){r.setup();r.arm();CanCapture.Snapshot c=r.ch.listenCanRxControl();
                eq(40,c.frameCount);eq(6,c.frameCountAtAcquisitionEnd);eq(34,c.frameCount-c.frameCountAtAcquisitionEnd);
                eq(1,c.notificationCountAtAcquisitionEnd);eq(200,c.bytesAtAcquisitionEnd);yes(c.stopConfirmed && c.stopWriteConfirmed);
                yes(c.adapterBufferFull && c.incomplete() && c.captureTotalMillis>c.captureElapsedMillis);
                eq(1,Collections.frequency(r.writes,"ATMA\r"));eq(1,Collections.frequency(r.writes," "));yes(!r.writes.contains("\r"));
                String j=HvacJournal.captureJson(c);yes(j.contains("\"drainFrameCount\":34"));yes(j.contains("\"adapterBufferFull\":true"));
                yes(j.contains("\"incomplete\":true"));yes(j.contains("Includes stop drain"));}
        });
        test("presence and recovery eligibility do not erase any capture error flags or bytes",()->{
            CanCapture.Snapshot c=recorded();String before=HvacJournal.captureJson(c);yes(CanRxAudit.matchingControlRxObserved(c));yes(CanRxAudit.controlRecoveryAllowed(c));
            eq(before,HvacJournal.captureJson(c));yes(c.incomplete());yes(c.adapterBufferFull);eq(2,c.invalidFrameLines);
        });
        test("unknown stop, GATT, parser storage, user stop and CAN errors block recovery individually",()->{
            for(int i=0;i<15;i++){
                CanCapture.Snapshot c=recorded();switch(i){
                    case 0:c.stopConfirmed=false;break;case 1:c.promptSeen=false;break;case 2:c.stopWriteConfirmed=false;break;
                    case 3:c.stopError="GATT";break;case 4:c.adapterError="CAN ERROR";break;case 5:c.unexpectedIdOutsideFilter=true;break;
                    case 6:c.sessionBudgetExceeded=true;break;case 7:c.lateStartWrite=true;break;case 8:c.truncated=true;break;
                    case 9:c.droppedFrames=1;break;case 10:c.droppedPackets=1;break;case 11:c.nonAsciiBytes=1;break;
                    case 12:c.overlongLines=1;break;case 13:c.endReason="USER_STOP";break;case 14:c.frameCountAtAcquisitionEnd=0;break;
                }yes(!CanRxAudit.controlRecoveryAllowed(c));
            }
        });
        test("a malformed-only or wrong-ID capture is never a positive control",()->{
            CanCapture.Snapshot c=recorded();c.frames.clear();yes(!CanRxAudit.matchingControlRxObserved(c));yes(!CanRxAudit.controlRecoveryAllowed(c));
            CanCapture.Stream s=new CanCapture.Stream(0,0x373);s.accept(ascii(CanRxChecks.ECC));CanCapture.Snapshot ecc=s.finish();ecc.startWriteConfirmed=true;
            yes(!CanRxAudit.matchingControlRxObserved(ecc));yes(!CanRxAudit.controlRecoveryAllowed(ecc));
        });
        test("real burst replay reaches ECC and post-control only through reacknowledged reset",()->{
            try(Replay r=new Replay()){r.setup();CanRxAudit.Result a=r.audit();
                eq("CONTROL_RX_PRESENT_ECC_NOT_OBSERVED_WITH_CONTROL_LOSS",a.code);yes(a.differentialPhasesCompleted);
                yes(a.beforeRxObserved() && a.afterRxObserved() && a.controlStreamIncomplete() && a.controlHasOverflow());
                yes(a.eccCapturesComplete() && !a.eccRxObserved() && !a.allCapturesComplete());eq(1,a.recoveriesAttempted);eq(1,a.recoveriesCompleted);
                eq(4,a.phases.size());eq(40,a.phases.get(0).capture.frameCount);eq(40,a.phases.get(3).capture.frameCount);
                int firstStop=r.writes.indexOf(" "),reset=r.writes.subList(firstStop+1,r.writes.size()).indexOf("ATZ\r")+firstStop+1;
                int ecc=r.writes.indexOf("ATCF373\r");yes(firstStop<reset && reset<ecc);
                List<String> commands=new ArrayList<>();for(String s:CanRxChecks.SETUP)commands.add(s+"\r");
                eq(commands,r.writes.subList(reset,reset+commands.size()));
                for(String s:r.writes)yes(PassiveNetworkPlan.hvacWireBytesAllowed(ascii(s),true));
            }
        });
        test("ECC reception remains separately observable while both controls are explicitly lossy",()->{
            try(Replay r=new Replay()){r.setup();r.eccFrame=true;CanRxAudit.Result a=r.audit();eq("ECC_RX_OBSERVED_WITH_CONTROLS_WITH_CONTROL_LOSS",a.code);
                yes(a.eccRxObserved() && a.eccCapturesComplete() && a.beforeRxObserved() && a.afterRxObserved());yes(!a.allCapturesComplete());}
        });
        test("reset contamination or rejected passive settings never permit a 373 monitor",()->{
            for(String cmd:new String[]{"ATZ","ATI","ATRV","ATCSM1","ATDPN"})try(Replay r=new Replay()){
                r.setup();r.badRecovery=cmd;fails(()->r.audit());yes(!r.writes.contains("ATCF373\r"));yes(!r.writes.contains("ATCRA373\r"));
                eq(1,Collections.frequency(r.writes,"ATMA\r"));
            }
        });
        test("missing stop prompt prevents reset/rearm even though matching RX was observed",()->{
            try(Replay r=new Replay()){r.setup();r.noPrompt=true;CanRxAudit.Result a=r.audit();eq("CONTROL_CAPTURE_INCOMPLETE",a.code);
                yes(a.beforeRxObserved());eq(0,a.recoveriesAttempted);yes(!r.writes.contains("ATCF373\r"));yes(!a.allCapturesComplete());}
        });
        test("ECC overflow is not accepted by the control exception",()->{
            try(Replay r=new Replay()){r.setup();r.eccFrame=true;r.eccOverflow=true;CanRxAudit.Result a=r.audit();eq("ECC_CAPTURE_INCOMPLETE",a.code);
                yes(!a.differentialPhasesCompleted && !a.eccCapturesComplete() && a.eccRxObserved());eq(2,a.phases.size());}
        });
        System.out.println("TOTAL "+checks+" CAN regression checks passed; RECORDED BYTES REPLAYED AS SYNTHETIC, no new vehicle validation.");
    }
}
