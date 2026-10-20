import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Fake GATT + ELM only. All replayed bytes are explicitly synthetic, never new vehicle measurements. */
public final class CanRxChecks {
    interface Check {void run() throws Exception;}
    static int count;
    static byte[] ascii(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static void yes(boolean value){if(!value)throw new AssertionError("Condition failed");}
    static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void test(String name,Check c)throws Exception{c.run();count++;System.out.println("PASS "+name);}
    static void fails(Check c)throws Exception{try{c.run();}catch(IllegalArgumentException|IllegalStateException|SecurityException e){return;}throw new AssertionError("Expected fail closed");}
    static final String ECC="373 8 94 0E 2A 2A 01 00 00 10\r";
    // Payload from the owner's 15 Oct prefix. Replaying this is NOT physical CAN RX.
    static final String CONTROL="0E9 8 2D F0 FF FF 9E 57 FF FF\r";
    static final String[] SETUP={"ATZ","ATE0","ATI","ATRV","ATL0","ATS1","ATH1","ATSP6","ATCAF0","ATCFC0","ATCSM1","ATD1","ATDPN"};
    static class FakeGatt implements AutoCloseable {
        final ScheduledExecutorService callbacks=Executors.newSingleThreadScheduledExecutor();
        final List<String> queued=new CopyOnWriteArrayList<>();
        final BleIoJournal io=new BleIoJournal();
        final GattWriteGate gate=new GattWriteGate((k,id,b,s)->io.add(k,System.nanoTime(),id,b,s,"2AF1","SYNTHETIC_GATT"));
        final ElmChannel ch=ElmChannel.forCanRxAudit(this::write,20000);
        volatile String id="",method="";volatile boolean control=true,ecc,alternate=true,overflow,eccViaCra;
        volatile boolean rejectMa,dropMaCallback,stopPrompt=true;
        int monitors;
        void write(byte[] bytes)throws Exception{
            yes(PassiveNetworkPlan.hvacWireBytesAllowed(bytes,true));
            gate.write(bytes,100,b->{
                String s=new String(b,StandardCharsets.US_ASCII);queued.add(s);
                if(s.equals("ATMA\r") && rejectMa)return 201;
                // Deliberately deliver RX before write callback; stream must already be armed.
                callbacks.schedule(()->{
                    if(s.equals("ATMA\r")){
                        monitors++;
                        if("0E9".equals(id) && ("CRA".equals(method)?alternate:control))rx(CONTROL+CONTROL+CONTROL);
                        else if("373".equals(id) && (ecc || (eccViaCra && "CRA".equals(method))))rx(ECC);
                        if(overflow)rx("BUFFER FULL\r>");
                    }else if(s.equals(" ")){if(stopPrompt)rx("STOPPED\r\r>");}
                    else {
                        String cmd=s.trim();if(cmd.equals("ATAR")){id="";method="";}
                        if(cmd.startsWith("ATCF")){id=cmd.substring(4);method="CM_CF";}
                        if(cmd.equals("ATCRA0E9") || cmd.equals("ATCRA373")){id=cmd.substring(5);method="CRA";}
                        rx(cmd.equals("ATDPN")?"6\r>":cmd.equals("ATZ")||cmd.equals("ATI")?"ELM327 v2.2\r>":cmd.equals("ATRV")?"14.1V\r>":"OK\r>");
                    }
                },1,TimeUnit.MILLISECONDS);
                if(!(s.equals("ATMA\r") && dropMaCallback))callbacks.schedule(()->gate.complete(0),3,TimeUnit.MILLISECONDS);
                return 0;
            });
        }
        void rx(String text){io.add("RX_NOTIFICATION",System.nanoTime(),0,ascii(text),-1,"2AF0","SYNTHETIC_ELM_CHANNEL");ch.receive(ascii(text));}
        void setup()throws Exception{for(String cmd:SETUP)ch.send(cmd,500);}
        void arm(String target)throws Exception{ch.send("ATAR",500);ch.send("ATCM7FF",500);ch.send("ATCF"+target,500);}
        CanRxAudit.Result audit()throws Exception{return new CanRxAudit(ch,new CanRxAudit.Observer(){public void phase(String n,String i,String m){}public void captured(CanRxAudit.Phase p){HvacJournal.captureJson(p.capture);}}).run();}
        public void close(){ch.close("synthetic test complete");gate.close();callbacks.shutdownNow();}
    }
    public static void main(String[] args)throws Exception{
        test("exact 7FF masks admit exactly one ID across all 2048 standard IDs",()->{
            for(int target:new int[]{0x534,0x373,0x0E9}){List<Integer> accepted=new ArrayList<>();for(int id=0;id<2048;id++)if(((id^target)&0x7FF)==0)accepted.add(id);eq(Collections.singletonList(target),accepted);}
        });
        test("physical-write firewall rejects ECU, wide filters, extra CR and command injection",()->{
            for(String s:new String[]{"5340000000000000001\r","1003\r","2701\r","3101\r","2EF19000\r","1101\r","ATCSM0\r","ATCM000\r","ATCRA534\r","ATZ\rATMA\r","\r","ATMA","ATMA\r\n"})yes(!PassiveNetworkPlan.hvacWireBytesAllowed(ascii(s),true));
            for(String s:new String[]{"ATMA\r"," ","ATAR\r","ATCM7FF\r","ATCF0E9\r","ATCRA0E9\r","ATCSM1\r"})yes(PassiveNetworkPlan.hvacWireBytesAllowed(ascii(s),true));
            yes(!PassiveNetworkPlan.hvacWireBytesAllowed(ascii("ATCF0E9\r"),false));yes(!PassiveNetworkPlan.hvacWireBytesAllowed(ascii("ATCRA0E9\r"),false));
        });
        test("GATT serializes writes until actual async callback, including ATMA",()->{
            List<String> events=new CopyOnWriteArrayList<>();GattWriteGate gate=new GattWriteGate((k,id,b,s)->events.add(k));
            ExecutorService work=Executors.newSingleThreadExecutor();CountDownLatch queued=new CountDownLatch(1);
            try{Future<?> f=work.submit(()->{try{gate.write(ascii("ATMA\r"),500,b->{queued.countDown();return 0;});}catch(Exception e){throw new RuntimeException(e);}});
                yes(queued.await(500,TimeUnit.MILLISECONDS));yes(!f.isDone());fails(()->gate.write(ascii("ATZ\r"),50,b->0));gate.complete(0);f.get(500,TimeUnit.MILLISECONDS);
                eq(Arrays.asList("TX_REQUEST","TX_QUEUE_RESULT","TX_CALLBACK","TX_COMPLETE"),events);
            }finally{gate.close();work.shutdownNow();}
        });
        test("a queue rejection cannot be labeled complete or retried",()->{
            List<String> events=new ArrayList<>();GattWriteGate g=new GattWriteGate((k,i,b,s)->events.add(k));
            fails(()->g.write(ascii("ATMA\r"),50,b->201));g.complete(0);fails(()->g.write(ascii("ATZ\r"),50,b->0));yes(!events.contains("TX_COMPLETE"));
        });
        test("timeout poisons GATT even if its callback arrives later",()->{
            GattWriteGate g=new GattWriteGate((k,i,b,s)->{});fails(()->g.write(ascii("ATMA\r"),10,b->0));g.complete(0);fails(()->g.write(ascii("ATZ\r"),10,b->0));
        });
        test("GATT error callback is failure; closing pending write wakes the worker",()->{
            GattWriteGate g=new GattWriteGate((k,i,b,s)->{});fails(()->g.write(ascii("ATMA\r"),50,b->{g.complete(133);return 0;}));
            GattWriteGate c=new GattWriteGate((k,i,b,s)->{});fails(()->c.write(ascii("ATMA\r"),50,b->{c.close();return 0;}));
        });
        test("early callback and mutable caller byte array preserve exact queued payload",()->{
            byte[] bytes=ascii("ATMA\r");List<String> events=new ArrayList<>();GattWriteGate g=new GattWriteGate((k,i,b,s)->events.add(k+":"+new String(b,StandardCharsets.US_ASCII)));
            g.write(bytes,50,b->{bytes[0]='X';g.complete(0);return 0;});yes(events.contains("TX_COMPLETE:ATMA\r"));
        });
        test("exact setup order is preserved through fake asynchronous GATT",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.arm("373");g.ecc=true;CanCapture.Snapshot s=g.ch.listenCanSnapshot(500);
                List<String> expected=new ArrayList<>();for(String cmd:SETUP)expected.add(cmd+"\r");expected.addAll(Arrays.asList("ATAR\r","ATCM7FF\r","ATCF373\r","ATMA\r"," "));
                eq(expected,g.queued);yes(s.startWriteConfirmed && s.frameCount==1 && s.stopConfirmed);yes(s.containsSyntheticInput);yes(g.io.json().contains("TX_COMPLETE"));}
        });
        test("rejected ATMA has no success timer, no stop write and no next AT command",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.arm("373");g.rejectMa=true;fails(()->g.ch.listenCanSnapshot(500));
                yes(!g.queued.contains(" "));yes(g.ch.hvacCaptureJson().contains("\"startWriteConfirmed\":false"));fails(()->g.ch.send("ATZ",100));}
        });
        test("lost ATMA callback is terminal even when synthetic CAN already arrived",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.arm("373");g.ecc=true;g.dropMaCallback=true;fails(()->g.ch.listenCanSnapshot(500));
                yes(g.ch.hvacCaptureJson().contains("\"retainedFrames\":1"));yes(g.ch.hvacCaptureJson().contains("\"startWriteConfirmed\":false"));yes(!g.queued.contains(" "));}
        });
        test("an AT command cannot interleave between ATMA and SPACE/prompt",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.arm("373");ExecutorService workers=Executors.newFixedThreadPool(2);
                try{Future<CanCapture.Snapshot> f=workers.submit(()->g.ch.listenCanSnapshot(500));long deadline=System.nanoTime()+1_000_000_000L;
                    while(g.ch.hvacProgress()[3]!=1 && System.nanoTime()<deadline)Thread.yield();yes(g.ch.hvacProgress()[3]==1);
                    Future<String> next=workers.submit(()->g.ch.send("ATZ",500));f.get(2,TimeUnit.SECONDS);next.get(2,TimeUnit.SECONDS);
                    yes(g.queued.indexOf(" ")<g.queued.lastIndexOf("ATZ\r"));eq(1,Collections.frequency(g.queued,"ATMA\r"));yes(!g.queued.contains("\r"));
                }finally{workers.shutdownNow();}}
        });
        test("replaying the three real STOPPED packets produces zero CAN, not hidden frames",()->{
            for(int run=0;run<3;run++){CanCapture.Stream s=new CanCapture.Stream(0,run==2?0x373:0x534);s.accept(ascii("STOPPED\r\r>"));
                CanCapture.Snapshot c=s.finish();eq(1,c.notificationCount);eq(10,c.receivedBytes);eq(0,c.frames.size());eq(0,c.invalidFrameLines);yes(c.adapterStopped && c.promptSeen && c.containsSyntheticInput);}
        });
        test("historical 0E9 prefix parses at every BLE fragmentation boundary as synthetic",()->{
            byte[] bytes=ascii(CONTROL);for(int split=1;split<bytes.length;split++){CanCapture.Stream s=new CanCapture.Stream(0,0x0E9);s.accept(Arrays.copyOfRange(bytes,0,split),1);s.accept(Arrays.copyOfRange(bytes,split,bytes.length),2);
                CanCapture.Snapshot c=s.finish();eq(1,c.frameCount);eq("0E9",c.frames.get(0).id);yes(HvacJournal.captureJson(c).contains("SYNTHETIC_TEST"));yes(!HvacJournal.captureJson(c).contains("\"decoded\""));}
        });
        test("control frame limit is a planned completion and stops once via SPACE",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.arm("0E9");CanCapture.Snapshot c=g.ch.listenCanRxControl();eq(3,c.frameCount);yes(c.frameLimitReached && !c.incomplete() && !c.windowElapsed);yes(c.captureElapsedMillis<1000);eq(1,Collections.frequency(g.queued," "));}
        });
        test("sandwiched controls distinguish zero ECC from globally missing CAN RX",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();CanRxAudit.Result r=g.audit();eq("CONTROL_RX_PRESENT_ECC_NOT_OBSERVED",r.code);eq(4,r.phases.size());eq(3,r.phases.get(0).capture.frameCount);eq(0,r.phases.get(1).capture.frameCount);eq("CRA",r.phases.get(2).method);eq(3,r.phases.get(3).capture.frameCount);}
        });
        test("a received ECC frame is distinguished from controls and synthetic origin remains explicit",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.ecc=true;CanRxAudit.Result r=g.audit();eq("ECC_RX_OBSERVED_WITH_CONTROLS",r.code);eq(1,r.phases.get(1).capture.frameCount);yes(r.phases.get(1).capture.containsSyntheticInput);}
        });
        test("zero controls use exact CRA fallback, never broad monitor or HVAC inference",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.control=false;g.alternate=false;CanRxAudit.Result r=g.audit();eq("NO_CONTROL_RX",r.code);eq(2,r.phases.size());yes(g.queued.contains("ATCRA0E9\r"));yes(!g.queued.contains("ATCF373\r"));yes(!g.queued.contains("ATCM000\r"));}
        });
        test("CRA-only control RX is rechecked with CM/CF before reporting API difference",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.control=false;CanRxAudit.Result r=g.audit();eq("FILTER_API_DIFFERENCE_OBSERVED",r.code);eq(3,r.phases.size());eq(0,r.phases.get(2).capture.frameCount);}
        });
        test("373 CRA-only result requires a CM/CF recheck and positive control after it",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.eccViaCra=true;CanRxAudit.Result r=g.audit();eq("ECC_RX_ONLY_VIA_CRA_OBSERVED",r.code);eq(5,r.phases.size());eq(0,r.phases.get(3).capture.frameCount);eq(3,r.phases.get(4).capture.frameCount);}
        });
        test("a disappearing post-control does not certify the zero ECC observation",()->{
            try(FakeGatt g=new FakeGatt(){@Override void rx(String text){super.rx(text);if(text.startsWith(CONTROL))control=false;}}){g.setup();CanRxAudit.Result r=g.audit();eq("CONTROL_AFTER_MISSING",r.code);}
        });
        test("overflow preserves the raw prefix and prevents later audit phases",()->{
            try(FakeGatt g=new FakeGatt()){g.setup();g.overflow=true;CanRxAudit.Result r=g.audit();eq("CONTROL_CAPTURE_INCOMPLETE",r.code);eq(1,r.phases.size());yes(r.phases.get(0).capture.adapterBufferFull);eq(3,r.phases.get(0).capture.frameCount);yes(!g.queued.contains("ATCF373\r"));}
        });
        test("raw journal preserves replies outside monitor and bytes rejected before parser",()->{
            BleIoJournal io=new BleIoJournal();ElmChannel ch=ElmChannel.forHvac(b->{},500);
            for(String route:new String[]{"NO_ELM_CHANNEL","OTHER_ENDPOINT","ELM_CHANNEL"})io.add("RX_NOTIFICATION",1,0,ascii(ECC),-1,"2AF0#wrong",route);
            ch.receive(ascii(ECC));yes(ch.hvacCaptureJson().equals("null"));String json=io.json();yes(json.contains("OTHER_ENDPOINT") && json.contains("NO_ELM_CHANNEL"));yes(json.contains(ElmParser.hexText(ascii(ECC))));
        });
        test("raw journal storage overrun is explicit, never a false complete export",()->{
            BleIoJournal io=new BleIoJournal();byte[] b=new byte[1000];for(int i=0;i<600;i++)yes(io.add("RX_NOTIFICATION",i,0,b,-1,"2AF0","ELM_CHANNEL"));yes(!io.add("RX_NOTIFICATION",601,0,b,-1,"2AF0","ELM_CHANNEL"));yes(io.json().contains("\"complete\":false"));yes(io.json().contains("\"droppedEvents\":1"));
        });
        System.out.println("TOTAL "+count+" CAN regression checks passed; SYNTHETIC GATT/ELM ONLY, no vehicle validation.");
    }
}
