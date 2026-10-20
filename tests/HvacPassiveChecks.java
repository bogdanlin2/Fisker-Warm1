import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic adapter only. Never connected to BLE, OBD or a vehicle. */
public final class HvacPassiveChecks {
    interface Check {void run() throws Exception;}
    static int count;
    static byte[] ascii(String s) {return s.getBytes(StandardCharsets.US_ASCII);}
    static void yes(boolean b) {if(!b)throw new AssertionError("Condition failed");}
    static void eq(Object a,Object b) {if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void test(String name,Check c) throws Exception {c.run();count++;System.out.println("PASS "+name);}
    static void blocked(Check c) throws Exception {try{c.run();}catch(SecurityException|IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError("Expected rejection");}
    static int oracle(byte[] p,int start,int width) {int v=0,b=start;for(int i=0;i<width;i++){v=(v<<1)|((p[b/8]>>>(b%8))&1);b=b%8==0?b+15:b-1;}return v;}
    static final String FRAME="373 8 94 0E 2A 2A 01 00 00 10\r";
    static class Adapter implements ElmChannel.Writer {
        ElmChannel ch;String output=FRAME;boolean stopPrompt=true;
        final List<String> writes=new CopyOnWriteArrayList<>();
        final ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();
        volatile boolean emitting;
        Adapter(long budget) {ch=ElmChannel.forHvac(this,budget);}
        public void write(byte[] bytes) {
            String s=new String(bytes,StandardCharsets.US_ASCII);writes.add(s);
            if(s.equals(" ")) {emitting=false;if(stopPrompt)ch.receive(ascii("STOPPED\r>"));return;}
            if(s.equals("ATMA\r")) {emitting=true;if(output!=null)ch.receive(ascii(output));return;}
            String cmd=s.trim();ch.receive(ascii(cmd.equals("ATDPN")?"6\r>":cmd.equals("ATI")||cmd.equals("ATZ")?"ELM327 v2.2\r>":cmd.equals("ATRV")?"13.1V\r>":"OK\r>"));
        }
        void arm(String id) throws Exception {
            for(String s:new String[]{"ATZ","ATE0","ATI","ATRV","ATL0","ATS1","ATH1","ATSP6","ATCAF0","ATCFC0","ATCSM1","ATD1","ATDPN","ATAR","ATCM7FF","ATCF"+id})ch.send(s,250);
        }
        void done() {producer.shutdownNow();ch.close("Synthetic test complete");}
    }
    public static void main(String[] args) throws Exception {
        test("callbacks from a duplicate GATT instance or different UUID cannot enter the stream",()->{
            yes(UartProfiles.sameEndpoint("18f0",1,"2af0",2,"18f0",1,"2af0",2));
            yes(!UartProfiles.sameEndpoint("18f0",1,"2af0",2,"18f0",3,"2af0",2));
            yes(!UartProfiles.sameEndpoint("18f0",1,"2af0",2,"18f0",1,"2af0",3));
            yes(!UartProfiles.sameEndpoint("fff0",1,"2af0",2,"18f0",1,"2af0",2));
            yes(!UartProfiles.sameEndpoint("18f0",1,"2af1",2,"18f0",1,"2af0",2));
        });
        test("534 switch and all ECC fields match an independent Motorola oracle",()->{
            Random r=new Random(733);
            for(int k=0;k<4096;k++){byte[] p=new byte[8];r.nextBytes(p);eq(oracle(p,57,2),HvacDecoder.decode("534",p)[0]);int[] d=HvacDecoder.decode("373",p);
                int[] starts={3,12,32,60},widths={4,4,1,2};for(int i=0;i<4;i++)eq(oracle(p,starts[i],widths[i]),d[i]);}
        });
        test("reserved and unknown signal values remain numeric, never become HVAC ON",()->{
            byte[] p=new byte[8];Arrays.fill(p,(byte)255);eq(3,HvacDecoder.decode("534",p)[0]);eq(15,HvacDecoder.decode("373",p)[0]);
            yes(HvacDecoder.describe("534",p).contains("зарезервовано"));blocked(()->HvacDecoder.decode("534",new byte[7]));blocked(()->HvacDecoder.decode("123",p));
        });
        test("every notification boundary preserves DLC, all bytes and completion timestamp",()->{
            byte[] bytes=ascii(FRAME);
            for(int split=1;split<bytes.length;split++){CanCapture.Stream s=new CanCapture.Stream(0,0x373);s.accept(Arrays.copyOfRange(bytes,0,split),3_000_000);s.accept(Arrays.copyOfRange(bytes,split,bytes.length),7_000_000);
                CanCapture.Snapshot n=s.finish();eq(1,n.frames.size());eq(8,n.frames.get(0).dlc);eq(7_000_000L,n.frames.get(0).receivedNanos);eq(2,n.packets.size());eq(8,n.frames.get(0).payload().length);}
        });
        test("300 unchanged CAN frames are retained individually, no sample deduplication",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,0x373);for(int i=0;i<300;i++)s.accept(ascii(FRAME),i*100_000_000L);
            CanCapture.Snapshot n=s.finish();eq(300,n.frameCount);eq(300,n.frames.size());eq(300,n.packets.size());eq(29_900_000_000L,n.frames.get(299).receivedNanos);
        });
        test("polling the parser does not create notifications or refresh last RX",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,0x373);s.accept(ascii(FRAME),4_000_000);
            for(int i=0;i<100;i++){eq(1,s.current().frameCount);eq(4_000_000L,s.current().frames.get(0).receivedNanos);}eq(1,s.finish().notificationCount);
        });
        test("replay is explicitly synthetic including an exported frame",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,0x373);s.accept(ascii(FRAME),4_000_000);String json=HvacJournal.captureJson(s.finish());
            yes(json.contains("\"containsSyntheticInput\":true"));yes(json.contains("\"source\":\"SYNTHETIC_TEST\""));
        });
        test("a mixed synthetic prefix cannot be relabeled as hardware by its final fragment",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,0x373);s.accept(ascii("373 8 94 "),1);s.accept(ascii("0E 2A 2A 01 00 00 10\r"),2,true);
            eq("SYNTHETIC_TEST",s.finish().frames.get(0).source);
        });
        test("partial payload and absent DLC are rejected with full raw evidence",()->{
            for(String text:new String[]{"373 8 00 00\r","373 00 00 00 00 00 00 00 00\r","373 8 00 00>"}){
                CanCapture.Stream s=new CanCapture.Stream(0,0x373);s.accept(ascii(text));CanCapture.Snapshot n=s.finish();eq(0,n.frames.size());yes(n.incomplete());yes(n.packets.size()>0);}
        });
        test("dropping a middle fragment never invents a valid eight-byte frame",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,0x373);s.accept(ascii("373 8 94 "));s.accept(ascii("00 10\r"));eq(0,s.finish().frameCount);yes(s.finish().incomplete());
        });
        test("an out-of-filter frame is retained and stops the measurement",()->{
            Adapter a=new Adapter(5000);try{a.arm("373");a.output="0E9 8 00 00 00 00 00 00 00 00\r";
                CanCapture.Snapshot n=a.ch.listenCanSnapshot(500);eq("FILTER_MISMATCH",n.endReason);yes(n.unexpectedIdOutsideFilter);eq("0E9",n.frames.get(0).id);yes(n.incomplete());a.ch.send("ATZ",250);
            }finally{a.done();}
        });
        test("finite storage limits report loss instead of silently dropping frames",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,0x373);for(int i=0;i<CanCapture.MAX_HVAC_FRAMES+3;i++)s.accept(ascii(FRAME));
            CanCapture.Snapshot n=s.finish();eq(CanCapture.MAX_HVAC_FRAMES,n.frames.size());yes(n.droppedFrames>0);yes(n.droppedPackets>0);yes(n.incomplete());
        });
        test("owner marks cannot impersonate received CAN frames",()->{
            HvacJournal marks=new HvacJournal();marks.mark("HMI_ON",1,2);yes(marks.marksJson().contains("OWNER_REPORTED"));blocked(()->marks.mark("CAN_RX",1,2));
        });
        test("the HVAC command channel rejects all vehicle payloads and broad filters",()->{
            Adapter a=new Adapter(5000);try{for(String cmd:new String[]{"ATCM000","ATCF000","ATCF123","ATCRA7F8","ATCSM0","ATCFC1","ATCAF1","ATSH534","1003","2701","31010110","2EF19000","1101","0100","0000000000000001","ATMA","ATZ\rATMA","AT SP6","atsp6"})blocked(()->a.ch.send(cmd,100));
                blocked(()->a.ch.sendAdapterDiagnostic("ATCM000",100));eq(0,a.writes.size());
            }finally{a.done();}
        });
        test("ATMA is blocked until silent mode, protocol and exact filter are acknowledged",()->{
            Adapter a=new Adapter(2000);try{blocked(()->a.ch.listenCanSnapshot(500));eq(0,a.writes.size());}finally{a.done();}
        });
        test("a rejected ATCSM1 reply prevents monitoring even if later settings answer OK",()->{
            Adapter a=new Adapter(5000){@Override public void write(byte[] b){if(new String(b,StandardCharsets.US_ASCII).equals("ATCSM1\r")){writes.add("ATCSM1\r");ch.receive(ascii("?\r>"));}else super.write(b);}};
            try{a.arm("373");blocked(()->a.ch.listenCanSnapshot(500));yes(!a.writes.contains("ATMA\r"));}finally{a.done();}
        });
        test("CM must be acknowledged before CF, and AR invalidates an armed filter",()->{
            Adapter a=new Adapter(5000);try{a.arm("373");a.ch.send("ATAR",250);blocked(()->a.ch.listenCanSnapshot(500));a.ch.send("ATCF373",250);a.ch.send("ATCM7FF",250);blocked(()->a.ch.listenCanSnapshot(500));}finally{a.done();}
        });
        test("BUFFER FULL plus prompt never sends a stop character or repeats ATMA",()->{
            Adapter a=new Adapter(5000);try{a.arm("373");a.output=FRAME+"BUFFER FULL\r>";CanCapture.Snapshot n=a.ch.listenCanSnapshot(500);
                yes(n.adapterBufferFull && n.stopConfirmed);eq("BUFFER_FULL",n.endReason);eq(1,n.frames.size());yes(!a.writes.contains(" ") && !a.writes.contains("\r"));a.ch.send("ATZ",250);
            }finally{a.done();}
        });
        test("BUFFER FULL without prompt preserves prefix and closes without blind retry",()->{
            Adapter a=new Adapter(1600);try{a.arm("373");a.output=FRAME+"BUFFER FULL";CanCapture.Snapshot n=a.ch.listenCanSnapshot(500);
                yes(!n.stopConfirmed && n.adapterBufferFull);eq(1,n.frames.size());yes(!a.writes.contains(" "));blocked(()->a.ch.send("ATZ",100));
            }finally{a.done();}
        });
        test("end-of-window stop uses one SPACE, with no idle CR restart",()->{
            Adapter a=new Adapter(5000);try{a.arm("373");CanCapture.Snapshot n=a.ch.listenCanSnapshot(500);
                eq("WINDOW_ELAPSED",n.endReason);yes(n.stopConfirmed && n.windowElapsed);eq(1,Collections.frequency(a.writes," "));yes(!a.writes.contains("\r"));
            }finally{a.done();}
        });
        test("prompt racing the stop cannot repeat ATMA",()->{
            Adapter a=new Adapter(5000){@Override public void write(byte[] b){if(new String(b,StandardCharsets.US_ASCII).equals(" ")){ch.receive(ascii(">"));writes.add(" ");}else super.write(b);}};
            try{a.arm("373");CanCapture.Snapshot n=a.ch.listenCanSnapshot(500);yes(n.stopConfirmed);eq(1,Collections.frequency(a.writes,"ATMA\r"));yes(!a.writes.contains("\r"));}finally{a.done();}
        });
        test("user stop drains prompt and retains the full prefix",()->{
            Adapter a=new Adapter(5000);ExecutorService work=Executors.newSingleThreadExecutor();
            try{a.arm("373");Future<CanCapture.Snapshot> f=work.submit(()->a.ch.listenCanSnapshot(30000));while(a.ch.hvacProgress()[0]==0)Thread.yield();yes(a.ch.requestMonitorStop());
                CanCapture.Snapshot n=f.get(2,TimeUnit.SECONDS);eq("USER_STOP",n.endReason);yes(n.stopConfirmed);eq(1,n.frames.size());yes(n.incomplete());
            }finally{a.done();work.shutdownNow();}
        });
        test("disconnect during monitoring exports a partial prefix, not an empty success",()->{
            Adapter a=new Adapter(5000);ExecutorService work=Executors.newSingleThreadExecutor();
            try{a.arm("373");Future<?> f=work.submit(()->{try{a.ch.listenCanSnapshot(30000);}catch(Exception expected){}});
                while(a.ch.hvacProgress()[0]==0)Thread.yield();a.ch.close("BLE_DISCONNECTED");f.get(2,TimeUnit.SECONDS);
                String j=a.ch.hvacCaptureJson();yes(j.contains("\"retainedFrames\":1"));yes(j.contains("CHANNEL_ABORT"));yes(j.contains("\"stopConfirmed\":false"));
            }finally{a.done();work.shutdownNow();}
        });
        test("stop timeout keeps data and prevents adapter reset on an unknown prompt",()->{
            Adapter a=new Adapter(3000);try{a.arm("373");a.stopPrompt=false;CanCapture.Snapshot n=a.ch.listenCanSnapshot(500);
                yes(!n.stopConfirmed && n.incomplete());eq(1,n.frames.size());blocked(()->a.ch.send("ATZ",100));
            }finally{a.done();}
        });
        test("reply timeout poisons the channel; a late prompt cannot revive it",()->{
            ElmChannel ch=ElmChannel.forHvac(b->{},1000);blocked(()->ch.send("ATI",30));ch.receive(ascii("ELM327 v2.2\r>"));blocked(()->ch.send("ATZ",100));
        });
        test("a quiet filtered bus completes without pretending notifications were lost or HVAC absent",()->{
            Adapter a=new Adapter(5000);try{a.arm("534");a.output=null;CanCapture.Snapshot n=a.ch.listenCanSnapshot(500);
                eq(0,n.frameCount);yes(n.stopConfirmed && !n.incomplete());yes(HvacJournal.captureJson(n).contains("\"notificationLossIndependentlyDetectable\":false"));
            }finally{a.done();}
        });
        test("a full 30-second synthetic ECC window retains every produced notification",()->{
            Adapter a=new Adapter(50000);try{a.arm("373");a.output=null;
                a.producer.scheduleAtFixedRate(()->{if(a.emitting)a.ch.receive(ascii(FRAME));},20,100,TimeUnit.MILLISECONDS);
                CanCapture.Snapshot n=a.ch.listenCanSnapshot(30000);yes(n.captureElapsedMillis>=30000);yes(n.frames.size()>=280);eq(n.frameCount,n.frames.size());
                yes(!n.incomplete() && n.stopConfirmed && n.containsSyntheticInput);eq(1,Collections.frequency(a.writes,"ATMA\r"));
            }finally{a.done();}
        });
        System.out.println("TOTAL "+count+" CAN regression checks passed; SYNTHETIC ADAPTER ONLY, no vehicle validation.");
    }
}
