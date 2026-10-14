import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Adapter emulation only: fragmented notifications, malformed data and stop races. */
public final class CanDiagnosticsChecks {
    interface Check {void run() throws Exception;}
    private static int count;
    private static byte[] ascii(String s) {return s.getBytes(StandardCharsets.US_ASCII);}
    private static void eq(Object a,Object b) {if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    private static void yes(boolean b) {if(!b)throw new AssertionError("Condition failed");}
    private static void test(String name,Check c) throws Exception {c.run();count++;System.out.println("PASS "+name);}
    private static void blocked(Check c) throws Exception {
        try {c.run();}catch(IllegalStateException|IllegalArgumentException|SecurityException expected) {return;}
        throw new AssertionError("Expected channel rejection");
    }
    public static void main(String[] args) throws Exception {
        test("unterminated payload is discarded, never invented as a short frame",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("0E9 01 02"));
            CanCapture.Snapshot n=s.finish();eq(0,n.frameCount);eq(1,n.partialLinesDiscarded);yes(n.truncated);
        });
        test("prompt cannot terminate an incomplete CAN payload",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("0E9 01 02>"));
            CanCapture.Snapshot n=s.finish();eq(0,n.frameCount);yes(n.promptSeen);yes(n.truncated);
        });
        test("complete short CAN frame remains valid",()->eq(1,CanCapture.parse("0E9 01 02\r").frameCount));
        test("DLC mismatch rejects an incomplete frame",()->{
            CanCapture.Snapshot n=CanCapture.parse("0E9 8 01 02\r");eq(0,n.frameCount);eq(1,n.invalidFrameLines);
        });
        test("zero DLC is a valid empty CAN data field",()->{
            CanCapture.Snapshot n=CanCapture.parse("123 0\r");eq(1,n.frameCount);yes(n.ids.get("123").samples.contains(""));
        });
        test("DLC too small cannot silently accept extra bytes",()->eq(0,CanCapture.parse("123 1 01 02\r").frameCount));
        test("parser accepts all valid 11-bit IDs, not just 0E9",()->{
            CanCapture.Snapshot n=CanCapture.parse("000 00\r123 01\r7FF FF\r");eq(3,n.frameCount);eq(3,n.ids.size());
        });
        test("29-bit IDs are explicitly outside ATSP6 capture",()->eq(0,CanCapture.parse("18DAF110 01 02\r").frameCount));
        test("every byte-boundary fragmentation preserves frames and overflow",()->{
            byte[] fixture=ascii("ATMA\r0E9 01 02\r123 8 00 01 02 03 04 05 06 07\rBUFFER FULL\r>");
            for(int split=0;split<=fixture.length;split++) {
                CanCapture.Stream s=new CanCapture.Stream();s.accept(Arrays.copyOfRange(fixture,0,split));s.accept(Arrays.copyOfRange(fixture,split,fixture.length));
                CanCapture.Snapshot n=s.finish();eq(2,n.frameCount);eq(2,n.framesAtFirstOverflow);yes(n.adapterBufferFull);yes(n.promptSeen);
            }
        });
        test("one-byte BLE packets preserve a fragmented overflow token",()->{
            CanCapture.Stream s=new CanCapture.Stream();byte[] fixture=ascii("0E9 01 02\rBUFFER FULL>");
            for(byte b:fixture)s.accept(new byte[]{b});CanCapture.Snapshot n=s.finish();eq(1,n.frameCount);yes(n.adapterBufferFull);yes(n.promptSeen);eq(fixture.length,n.receivedBytes);
        });
        test("overflow without a trailing CR is recorded immediately",()->{
            CanCapture.Stream s=new CanCapture.Stream(0);s.accept(ascii("123 01\rBUF"),10_000_000);s.accept(ascii("FER FULL"),25_000_000);
            yes(s.hasAdapterOverflow());CanCapture.Snapshot n=s.finish();eq(25L,n.firstOverflowMillis);eq(1,n.framesAtFirstOverflow);eq(n.receivedBytes,n.bytesAtFirstOverflow);
        });
        test("notification times and byte sizes are measured, not assumed",()->{
            CanCapture.Stream s=new CanCapture.Stream(0);s.accept(ascii("123 "),5_000_000);s.accept(ascii("01\r>"),17_000_000);
            CanCapture.Snapshot n=s.finish();eq(5L,n.firstNotificationMillis);eq(17L,n.lastNotificationMillis);eq(12L,n.maxNotificationGapMillis);eq(2,n.notificationCount);eq(4,n.minNotificationBytes);eq(4,n.maxNotificationBytes);
        });
        test("empty notifications do not invent throughput",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(new byte[0]);eq(0,s.finish().notificationCount);
        });
        test("CRLF does not consume two frame slots",()->{
            StringBuilder b=new StringBuilder();for(int i=0;i<1500;i++)b.append("123 01\r\n");CanCapture.Snapshot n=CanCapture.parse(b.toString());eq(1500,n.frameCount);yes(!n.truncated);
        });
        test("frame cap stays at 1500 and does not hide subsequent overflow",()->{
            CanCapture.Stream s=new CanCapture.Stream();for(int i=0;i<1502;i++)s.accept(ascii("123 01\r"));s.accept(ascii("BUFFER FULL\r>"));
            CanCapture.Snapshot n=s.finish();eq(1500,n.frameCount);yes(n.truncated);yes(n.adapterBufferFull);eq(1500,n.framesAtFirstOverflow);
        });
        test("oversized line is discarded and parsing recovers at the delimiter",()->{
            CanCapture.Stream s=new CanCapture.Stream();byte[] b=new byte[200];Arrays.fill(b,(byte)'A');s.accept(b);s.accept(ascii("\r123 01\r>"));
            CanCapture.Snapshot n=s.finish();eq(1,n.frameCount);eq(1,n.overlongLines);yes(n.truncated);
        });
        test("non-ASCII corruption cannot turn into a valid CAN frame",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("123 01 "));s.accept(new byte[]{(byte)255});s.accept(ascii("02\r345 03\r>"));
            CanCapture.Snapshot n=s.finish();eq(1,n.frameCount);yes(!n.ids.containsKey("123"));eq(1,n.nonAsciiBytes);yes(n.truncated);
        });
        test("documented ELM NULL insertion is ignored without inventing corruption",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(new byte[]{'1','2','3',' ',0,'0','1',13,'>',0});
            CanCapture.Snapshot n=s.finish();eq(1,n.frameCount);eq(2,n.ignoredNullBytes);yes(!n.truncated);
        });
        test("ELM command NULL insertion does not corrupt voltage parsing",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->ch[0].receive(new byte[]{'1','2',0,'.','6','V',13,'>'}),1000);eq("12.6V",ch[0].send("ATRV",100));
        });
        test("payload interrupted by overflow is discarded with a visible counter",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("123 01 BUFFER FULL\r>"));CanCapture.Snapshot n=s.finish();eq(0,n.frameCount);eq(1,n.partialLinesDiscarded);yes(n.adapterBufferFull);yes(n.truncated);
        });
        test("data following a prompt is rejected as a second stream",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("123 01\r>345 02\r"));CanCapture.Snapshot n=s.finish();eq(1,n.frameCount);yes(n.truncated);
        });
        test("finish is idempotent and late notifications cannot mutate a report",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("123 01\r>"));CanCapture.Snapshot n=s.finish();yes(n==s.finish());blocked(()->s.accept(ascii("345 02\r")));eq(1,n.frameCount);
        });
        test("adapter errors mark incomplete captures",()->{
            for(String error:Arrays.asList("CAN ERROR","BUS ERROR","BUS BUSY","RX ERROR","NO DATA","UNABLE TO CONNECT","?")) {
                CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii(error+"\r>"));CanCapture.Snapshot n=s.finish();eq(error,n.adapterError);yes(n.incomplete());
            }
        });
        test("BUFFER FULL with prompt must never send an idle CR and restart ATMA",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();
            ch[0]=new ElmChannel(b->{String cmd=new String(b,StandardCharsets.US_ASCII);writes.add(cmd);ch[0].receive(ascii("123 01\rBUFFER FULL\r>"));},2000);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);eq(Arrays.asList("ATMA\r"),writes);yes(n.stopConfirmed);yes(!n.stopSent);yes(n.incomplete());
        });
        test("overflow waits for a delayed final prompt without sending CR",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
            try {
                ch[0]=new ElmChannel(b->{writes.add(new String(b,StandardCharsets.US_ASCII));ch[0].receive(ascii("123 01\rBUFFER FULL\r"));timer.schedule(()->ch[0].receive(ascii(">")),20,TimeUnit.MILLISECONDS);},2000);
                CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);eq(1,writes.size());yes(n.stopConfirmed);yes(n.stopDrainMillis>0);
            }finally {timer.shutdownNow();}
        });
        test("normal stop drains the final frame before accepting the prompt",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
            try {
                ch[0]=new ElmChannel(b->{String cmd=new String(b,StandardCharsets.US_ASCII);writes.add(cmd);
                    if(cmd.equals("ATMA\r"))ch[0].receive(ascii("123 01\r345 02"));
                    else timer.schedule(()->ch[0].receive(ascii(" 03\rSTOPPED\r>")),20,TimeUnit.MILLISECONDS);},2000);
                CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);eq(2,n.frameCount);yes(n.stopSent);yes(n.stopWriteConfirmed);yes(n.stopConfirmed);yes(!n.incomplete());eq(Arrays.asList("ATMA\r","\r"),writes);
            }finally {timer.shutdownNow();}
        });
        test("a clean prompt permits a subsequent addressed adapter command",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->{String cmd=new String(b,StandardCharsets.US_ASCII);ch[0].receive(ascii(cmd.equals("ATMA\r")?"123 01\r>":"ELM327 v2.2\r>"));},2000);
            yes(ch[0].listenCanSnapshot(500).stopConfirmed);eq("ELM327 v2.2",ch[0].send("ATI",100));
        });
        test("missing overflow prompt closes the channel without repeating the command",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ch[0]=new ElmChannel(b->{writes.add(new String(b,StandardCharsets.US_ASCII));ch[0].receive(ascii("123 01\rBUFFER FULL"));},3000);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);yes(!n.stopConfirmed);blocked(()->ch[0].send("ATI",100));eq(1,writes.size());
        });
        test("stop write failure is reported and the channel cannot be reused",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->{if(b.length==1)throw new IllegalStateException("Emulated stop write failure");ch[0].receive(ascii("123 01\r"));},2000);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);yes(!n.stopConfirmed);yes(n.stopError!=null);yes(n.incomplete());blocked(()->ch[0].send("ATI",100));
        });
        test("ATMA write failure is terminal and does not send an idle CR",()->{
            int[] writes={0};ElmChannel ch=new ElmChannel(b->{writes[0]++;throw new IllegalStateException("Emulated ATMA write failure");},2000);
            blocked(()->ch.listenCanSnapshot(500));blocked(()->ch.send("ATI",100));eq(1,writes[0]);
        });
        test("expired session budget blocks ATMA before any write",()->{
            int[] writes={0};ElmChannel ch=new ElmChannel(b->writes[0]++,1);Thread.sleep(10);blocked(()->ch.listenCanSnapshot(500));eq(0,writes[0]);
        });
        test("session deadline also bounds monitoring and prompt drain",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ch[0]=new ElmChannel(b->{writes.add(new String(b,StandardCharsets.US_ASCII));ch[0].receive(ascii("123 01\r"));},50);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);yes(n.sessionBudgetExceeded);yes(n.incomplete());eq(Arrays.asList("ATMA\r"),writes);blocked(()->ch[0].send("ATI",100));
        });
        test("early adapter error is not restarted with an idle CR",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ch[0]=new ElmChannel(b->{writes.add(new String(b,StandardCharsets.US_ASCII));ch[0].receive(ascii("CAN ERROR\r>"));},2000);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);eq("CAN ERROR",n.adapterError);eq(1,writes.size());yes(n.incomplete());
        });
        test("unexpected early prompt is not a complete 500ms observation",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->ch[0].receive(ascii("123 01\r>")),2000);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);yes(n.stopConfirmed);yes(!n.windowElapsed);yes(n.incomplete());
        });
        test("slow start writer cannot claim a valid bounded CAN window",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->{if(b.length>1)Thread.sleep(510);ch[0].receive(ascii(b.length>1?"123 01\r":"STOPPED\r>"));},2500);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500);yes(n.lateStartWrite);yes(n.incomplete());yes(n.stopConfirmed);
        });
        test("raw compatibility view shares safe overflow completion",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ch[0]=new ElmChannel(b->{writes.add(new String(b,StandardCharsets.US_ASCII));ch[0].receive(ascii("123 01\rBUFFER FULL\r>"));},2000);
            yes(ch[0].listenCan(500).contains("BUFFER FULL"));eq(1,writes.size());
        });
        test("monitoring still rejects unsafe raw vehicle commands",()->{
            int[] writes={0};ElmChannel ch=new ElmChannel(b->writes[0]++,2000);for(String cmd:Arrays.asList("1101","2701","2EF19500","31010000","ATCM000","ATS0"))blocked(()->ch.send(cmd,100));eq(0,writes[0]);
        });
        test("valid buffer-full-free data still does not prove heating",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("123 01\r>"));CanCapture.Snapshot n=s.finish();n.stopConfirmed=true;n.windowElapsed=true;yes(!n.incomplete());yes(!PkcOfflineCodec.canSendToVehicle());
        });
        networkAuditChecks();
        System.out.println("TOTAL "+count+" CAN regression checks passed; emulated adapter only.");
    }
    private static void networkAuditChecks() throws Exception {
        test("eleven receive groups cover every other 11-bit ID exactly once",()->{
            eq(11,PassiveNetworkPlan.groups().length);
            for(int id=0;id<=0x7FF;id++) {
                int hits=0;for(PassiveNetworkPlan.Group group:PassiveNetworkPlan.groups())if(group.accepts(id))hits++;
                eq(id==0x0E9?0:1,hits);
            }
        });
        test("receive groups do not invent 29-bit coverage",()->{
            for(PassiveNetworkPlan.Group group:PassiveNetworkPlan.groups()) {yes(!group.accepts(-1));yes(!group.accepts(0x800));yes(!group.accepts(0x18DAF110));}
        });
        test("diagnostic group table cannot be mutated by callers",()->{
            PassiveNetworkPlan.Group[] copy=PassiveNetworkPlan.groups();copy[0]=null;yes(PassiveNetworkPlan.groups()[0]!=null);
        });
        test("all fixed receive masks are canonically encoded and allowed",()->{
            for(PassiveNetworkPlan.Group group:PassiveNetworkPlan.groups()) {
                yes(group.maskCommand().matches("ATCM[0-7][0-9A-F]{2}"));yes(group.filterCommand().matches("ATCF[0-7][0-9A-F]{2}"));
                yes(PassiveNetworkPlan.diagnosticAdapterCommand(group.maskCommand()));yes(PassiveNetworkPlan.diagnosticAdapterCommand(group.filterCommand()));
                eq(group.firstId,(group.firstId & group.mask));yes(group.accepts(group.firstId));yes(group.accepts(group.lastId));
            }
        });
        test("new adapter path forbids ECU/control/wake/baud/ACK-on commands",()->{
            int[] writes={0};ElmChannel ch=new ElmChannel(b->writes[0]++,3000);
            for(String command:Arrays.asList("1101","2701","2EF19500","31010000","1003","3E00","ATCSM0","ATBRD23","ATSP7","STMA","STP31","ATFCSH7A2","ATCRA0E9","ATS0","ATCM123","ATCF123"))
                blocked(()->ch.sendAdapterDiagnostic(command,100));
            eq(0,writes[0]);
        });
        test("adapter diagnostics reject injected and lowercase commands",()->{
            int[] writes={0};ElmChannel ch=new ElmChannel(b->writes[0]++,3000);
            for(String command:Arrays.asList("atcs","ATCS\r1101","ATCM000\nATCSM0"," ATCS","ATCS ","AT CS"))blocked(()->ch.sendAdapterDiagnostic(command,100));eq(0,writes[0]);
        });
        test("local status inspection leaves original raw send firewall intact",()->{
            int[] writes={0};ElmChannel ch=new ElmChannel(b->writes[0]++,3000);blocked(()->ch.send("ATCS",100));eq(0,writes[0]);
        });
        test("fixed adapter diagnostics use the same fragmented prompt channel",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->{eq("ATCS\r",new String(b,StandardCharsets.US_ASCII));ch[0].receive(ascii("T:00 R:"));ch[0].receive(ascii("00\r>"));},3000);
            eq("T:00 R:00",ch[0].sendAdapterDiagnostic("ATCS",100));
        });
        test("unsupported local query remains raw and does not invent support",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->ch[0].receive(ascii("?\r>")),3000);eq("?",ch[0].sendAdapterDiagnostic("ATCS",100));
        });
        test("diagnostic timeout closes all paths without retries",()->{
            int[] writes={0};ElmChannel ch=new ElmChannel(b->writes[0]++,3000);blocked(()->ch.sendAdapterDiagnostic("ATCS",5));blocked(()->ch.sendAdapterDiagnostic("ATDPN",100));eq(1,writes[0]);
        });
        test("a diagnostic channel cannot reuse a stale authorized ECU route",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ch[0]=new ElmChannel(b->{writes.add(new String(b,StandardCharsets.US_ASCII));ch[0].receive(ascii("OK\r>"));},3000);
            for(String cmd:Arrays.asList("ATSH7A2","ATCRA7AA","ATFCSH7A2"))ch[0].send(cmd,100);
            ch[0].sendAdapterDiagnostic("ATCSM1",100);int before=writes.size();
            for(String cmd:Arrays.asList("22F195","ATSH7A2","ATCFC1","ATCAF1","ATAL"))blocked(()->ch[0].send(cmd,100));eq(before,writes.size());
        });
        test("ATZ cannot re-enable the ECU path of a diagnostic-only channel",()->{
            ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->ch[0].receive(ascii("OK\r>")),3000);
            ch[0].sendAdapterDiagnostic("ATCSM1",100);ch[0].send("ATZ",100);blocked(()->ch[0].send("ATSH7A2",100));
        });
        test("bounded raw trace preserves the malformed line and prompt",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,true);String raw="0E9 01 02\r0E9 8 01 02\rBUFFER FULL\r>";s.accept(ascii(raw),10_000_000);
            CanCapture.Snapshot n=s.finish();eq(raw,n.rawAsciiPrefix);eq(1,n.invalidFrameLines);eq(Arrays.asList("0E9 8 01 02"),n.invalidLineSamples);eq(1,n.notificationTrace.size());
        });
        test("DLC presence is evidence, not inferred from an adapter OK",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("123 2 01 02\r345 01 02\r456 8 01 02\r>"));CanCapture.Snapshot n=s.finish();
            eq(2,n.frameCount);eq(1,n.framesWithDlc);eq(1,n.framesWithoutDlc);eq(1,n.invalidFrameLines);
        });
        test("raw trace cap never masks actual parsed CAN counts",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,true);for(int i=0;i<400;i++)s.accept(ascii("123 01\r"),i*1_000_000L);
            CanCapture.Snapshot n=s.finish();eq(400,n.frameCount);yes(n.rawTraceTruncated);eq(CanCapture.MAX_RAW_TRACE_BYTES,n.rawAsciiPrefix.length());yes(!n.truncated);
        });
        test("notification trace is bounded independently of capture correctness",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,true);for(int i=0;i<70;i++)s.accept(ascii("123 01\r"),i*1_000_000L);
            CanCapture.Snapshot n=s.finish();eq(70,n.notificationCount);eq(64,n.notificationTrace.size());yes(n.notificationTraceTruncated);yes(!n.truncated);
        });
        test("trace arrival timestamps refer to the notification entry",()->{
            CanCapture.Stream s=new CanCapture.Stream(0,true);s.accept(ascii("123 01\r>"),8_000_000);s.noteReceiveLockWait(250_000);
            CanCapture.Snapshot n=s.finish();eq(8000L,n.notificationTrace.get(0).arrivalMicros);eq(250L,n.maxReceiveLockWaitMicros);
        });
        test("normal capture avoids raw notification trace storage",()->{
            CanCapture.Stream s=new CanCapture.Stream();s.accept(ascii("123 01\r>"));CanCapture.Snapshot n=s.finish();eq(null,n.rawAsciiPrefix);eq(0,n.notificationTrace.size());
        });
        test("invalid-line samples cannot grow unbounded",()->{
            CanCapture.Stream s=new CanCapture.Stream();for(int i=0;i<20;i++)s.accept(ascii("not a frame\r"));CanCapture.Snapshot n=s.finish();eq(20,n.invalidFrameLines);eq(3,n.invalidLineSamples.size());
        });
        test("filtered passive capture uses only local AT configuration and one stop",()->{
            ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();
            ch[0]=new ElmChannel(b->{String cmd=new String(b,StandardCharsets.US_ASCII);writes.add(cmd);
                ch[0].receive(ascii(cmd.equals("ATMA\r")?"400 01\r":cmd.equals("\r")?"STOPPED\r>":"OK\r>"));},3000);
            ch[0].sendAdapterDiagnostic("ATCSM1",100);PassiveNetworkPlan.Group group=PassiveNetworkPlan.groups()[0];
            ch[0].sendAdapterDiagnostic(group.maskCommand(),100);ch[0].sendAdapterDiagnostic(group.filterCommand(),100);
            CanCapture.Snapshot n=ch[0].listenCanSnapshot(500,true);eq(1,n.frameCount);yes(n.stopConfirmed);yes(!n.incomplete());yes(group.accepts(Integer.parseInt(n.ids.keySet().iterator().next(),16)));
            for(String cmd:writes)yes(cmd.equals("\r") || cmd.startsWith("AT"));
        });
    }
}
