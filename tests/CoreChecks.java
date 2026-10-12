import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Deterministic checks with genuine owner ISO-TP fixtures and synthetic adverse cases. */
public final class CoreChecks {
    interface Check { void run() throws Exception; }
    private static int count=0;
    private static void test(String name,Check c) throws Exception {c.run();count++;System.out.println("PASS "+name);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
    private static void require(boolean b){if(!b)throw new AssertionError("Condition failed");}
    private static void denied(Check c) throws Exception {boolean raised=false;try{c.run();}catch(SecurityException|IllegalArgumentException|IllegalStateException e){raised=true;}if(!raised)throw new AssertionError("Expected denial");}
    private static final String PKC="7AA101262F195463136\n7AA2130383038313330\n7AA223032312E33CCCC";
    private static final String ECC="7F8100C62F195454343\n7F821333935203234AA";
    private static final String ICC="7B9100C62F195494343\n7B921333930433439FF";
    private static ReadOnlyPolicy configured(){ReadOnlyPolicy p=new ReadOnlyPolicy();p.checkAndRecord("ATSH7A2");p.checkAndRecord("ATCRA7AA");p.checkAndRecord("ATFCSH7A2");return p;}
    private static HeatSafety.Evidence future(){return new HeatSafety.Evidence(true,true,true,true,true,true,true);}
    private static HeatSafety.Snapshot snapshot(Boolean park,Double speed,Double v,Double soc,long age,boolean auth){return new HeatSafety.Snapshot(park,speed,v,soc,age,auth);}
    public static void main(String[] args) throws Exception {
        test("genuine PKC21.3 owner reply",()->equal("F160808130021.3",ElmParser.printable(ElmParser.identityFramed(PKC,"7AA",0xF195))));
        test("genuine ECC24 owner reply",()->equal("ECC395 24",ElmParser.printable(ElmParser.identityFramed(ECC,"7F8",0xF195))));
        test("genuine ICC49 uses 7B9",()->equal("ICC390C49",ElmParser.printable(ElmParser.identityFramed(ICC,"7B9",0xF195))));
        test("spaced CAN frames",()->equal("ECC395 24",ElmParser.printable(ElmParser.identityFramed("7F8 10 0C 62 F1 95 45 43 43\r7F8 21 33 39 35 20 32 34 AA>","7F8",0xF195))));
        test("DLC is checked",()->equal("F160808130021.3",ElmParser.printable(ElmParser.identityFramed("7AA 8 10 12 62 F1 95 46 31 36\n7AA 8 21 30 38 30 38 31 33 30\n7AA 8 22 30 32 31 2E 33 CC CC","7AA",0xF195))));
        test("bad DLC rejected",()->denied(()->ElmParser.identityFramed("7AA 7 03 62 F1 95 31 00 00 00","7AA",0xF195)));
        test("single frame",()->equal("PKC",ElmParser.printable(ElmParser.identityFramed("7AA 06 62 F1 95 50 4B 43 00","7AA",0xF195))));
        test("numbered ELM parser offline",()->equal("ECC395 24",ElmParser.printable(ElmParser.identity("00C\n0: 62 F1 95 45 43 43\n1: 33 39 35 20 32 34","7F8",0xF195))));
        test("live mode requires responder",()->denied(()->ElmParser.identityFramed("62F195504B43","7AA",0xF195)));
        test("echo stripped",()->equal("ECC395 24",ElmParser.printable(ElmParser.identityFramed("22F195\r"+ECC+">","7F8",0xF195))));
        test("wrong DID rejected",()->denied(()->ElmParser.identityFramed(PKC,"7AA",0xF188)));
        test("wrong responder rejected",()->denied(()->ElmParser.identityFramed(PKC,"7F8",0xF195)));
        test("missing consecutive frame rejected",()->denied(()->ElmParser.identityFramed("7AA101262F195463136\n7AA223032312E33CCCC","7AA",0xF195)));
        test("truncated ISO-TP rejected",()->denied(()->ElmParser.identityFramed("7AA101262F195463136\n7AA2130383038313330","7AA",0xF195)));
        test("extra ISO-TP rejected",()->denied(()->ElmParser.identityFramed(PKC+"\n7AA2300000000000000","7AA",0xF195)));
        test("truncated formatted rejected",()->denied(()->ElmParser.identity("012\n0: 62F195463136\n1: 30383038313330","7AA",0xF195)));
        test("out-of-order formatted rejected",()->denied(()->ElmParser.identity("012\n1: 62F195463136","7AA",0xF195)));
        test("NRC31 is failure",()->denied(()->ElmParser.identityFramed("7AA037F223155555555","7AA",0xF195)));
        test("NRC78 is pending, never success",()->denied(()->ElmParser.identityFramed("7AA037F227855555555","7AA",0xF195)));
        for(String status:Arrays.asList("NO DATA","BUFFER FULL","CAN ERROR","STOPPED","UNABLE TO CONNECT","?"))
            test("adapter error "+status,()->denied(()->ElmParser.identityFramed(status,"7AA",0xF195)));
        test("non-hex text rejected",()->denied(()->ElmParser.identity("Success","7AA",0xF195)));
        test("mixed formats rejected",()->denied(()->ElmParser.identity(ECC+"\n62F195504B43","7F8",0xF195)));
        test("binary identity not invented ASCII",()->require(ElmParser.printable(new byte[]{0x01,0x02,0x03}).startsWith("HEX")));
        test("voltage normal",()->equal(12.6,ElmParser.voltage("12.6V>")));
        test("voltage echo",()->equal(13.4,ElmParser.voltage("ATRV\r13.4 V\r>")));
        test("voltage garbage unknown",()->equal(null,ElmParser.voltage("NO DATA")));
        test("voltage nan unknown",()->equal(null,ElmParser.voltage("NaNV")));
        test("voltage mixed unknown",()->equal(null,ElmParser.voltage("12.6V\n11.0V")));
        test("voltage absurd unknown",()->equal(null,ElmParser.voltage("99V")));
        test("low voltage blocks UDS policy",()->require(!ReadOnlyPolicy.diagnosticVoltageAcceptable(11.9)));
        test("unknown voltage blocks",()->require(!ReadOnlyPolicy.diagnosticVoltageAcceptable(null)));
        test("infinite voltage blocks",()->require(!ReadOnlyPolicy.diagnosticVoltageAcceptable(Double.POSITIVE_INFINITY)));
        test("addressed identity read allowed",()->configured().checkAndRecord("22F195"));
        test("unaddressed identity blocked",()->denied(()->new ReadOnlyPolicy().checkAndRecord("22F195")));
        test("mismatched RX blocked",()->denied(()->configured().checkAndRecord("ATCRA7B9")));
        test("route change invalidates RX",()->{ReadOnlyPolicy p=configured();p.checkAndRecord("ATSH7F0");denied(()->p.checkAndRecord("22F195"));});
        test("ATZ invalidates route",()->{ReadOnlyPolicy p=configured();p.checkAndRecord("ATZ");denied(()->p.checkAndRecord("22F195"));});
        for(String unsafe:Arrays.asList("1101","14FFFFFF","1003","2701","2EF19500","31010000","3E00","3400","ATLP","ATSH7C2","ATSH7D0","ATSH7F4","ATSH781","22EFF2","ATCRA","ATPP00OFF","ATSH7A2\r1101","ATSH7A2\n1101","atsh7a2"," ATZ"))
            test("wire firewall blocks "+unsafe.replace('\r','/').replace('\n','/'),()->denied(()->configured().checkAndRecord(unsafe)));
        test("all genuine routes paired",()->{for(ReadOnlyPolicy.Route r:ReadOnlyPolicy.routes()){ReadOnlyPolicy p=new ReadOnlyPolicy();p.checkAndRecord("ATSH"+r.tx);p.checkAndRecord("ATCRA"+r.rx);p.checkAndRecord("ATFCSH"+r.tx);for(int d:ReadOnlyPolicy.identityDids())p.checkAndRecord(String.format("22%04X",d));}});
        test("production heat always blocked",()->require(!HeatSafety.blockers(HeatSafety.PRODUCTION_EVIDENCE,snapshot(true,0.,13.,75.,0,true),20,true).isEmpty()));
        test("synthetic future prerequisites only",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,75.,0,true),20,true).isEmpty()));
        test("missing telemetry blocks",()->require(HeatSafety.blockers(future(),null,20,true).contains("NO_LIVE_TELEMETRY")));
        test("drive gear blocks",()->require(HeatSafety.blockers(future(),snapshot(false,0.,13.,75.,0,true),20,true).contains("PARK_NOT_CONFIRMED")));
        test("unknown park blocks",()->require(HeatSafety.blockers(future(),snapshot(null,0.,13.,75.,0,true),20,true).contains("PARK_NOT_CONFIRMED")));
        test("moving blocks",()->require(HeatSafety.blockers(future(),snapshot(true,1.,13.,75.,0,true),20,true).contains("STATIONARY_NOT_CONFIRMED")));
        test("unknown speed blocks",()->require(HeatSafety.blockers(future(),snapshot(true,null,13.,75.,0,true),20,true).contains("STATIONARY_NOT_CONFIRMED")));
        test("stale data blocks",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,75.,10001,true),20,true).contains("TELEMETRY_STALE_OR_TIME_INVALID")));
        test("future timestamp blocks",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,75.,-1,true),20,true).contains("TELEMETRY_STALE_OR_TIME_INVALID")));
        test("low HV blocks",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,19.,0,true),20,true).contains("HV_SOC_LOW_OR_UNKNOWN")));
        test("unknown HV blocks",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,null,0,true),20,true).contains("HV_SOC_LOW_OR_UNKNOWN")));
        test("nan HV blocks",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,Double.NaN,0,true),20,true).contains("HV_SOC_LOW_OR_UNKNOWN")));
        test("no authentication blocks",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,75.,0,true),20,false).contains("REQUEST_NOT_AUTHENTICATED")));
        test("untrusted telemetry blocks",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,75.,0,false),20,true).contains("TELEMETRY_SOURCE_NOT_AUTHENTICATED")));
        test("duration capped",()->require(HeatSafety.blockers(future(),snapshot(true,0.,13.,75.,0,true),21,true).contains("DURATION_OUTSIDE_15_TO_20_MINUTES")));
        test("independent vehicle timeout required",()->require(HeatSafety.blockers(new HeatSafety.Evidence(true,true,true,true,false,true,true),snapshot(true,0.,13.,75.,0,true),20,true).contains("VEHICLE_SIDE_TIMEOUT_NOT_VERIFIED")));
        test("weekly Kyiv local clock",()->equal(Instant.parse("2026-10-12T04:00:00Z"),LocalSchedule.next(Instant.parse("2026-10-10T19:00:00Z"),ZoneId.of("Europe/Kyiv"),LocalTime.of(7,0),EnumSet.of(DayOfWeek.MONDAY))));
        test("weekly DST changes UTC hour",()->equal(Instant.parse("2026-10-26T05:00:00Z"),LocalSchedule.next(Instant.parse("2026-10-25T12:00:00Z"),ZoneId.of("Europe/Kyiv"),LocalTime.of(7,0),EnumSet.of(DayOfWeek.MONDAY))));
        test("DST missing time skipped",()->equal(LocalDate.of(2026,4,5),LocalSchedule.next(Instant.parse("2026-03-28T10:00:00Z"),ZoneId.of("Europe/Kyiv"),LocalTime.of(3,30),EnumSet.of(DayOfWeek.SUNDAY)).atZone(ZoneId.of("Europe/Kyiv")).toLocalDate()));
        test("DST overlap not duplicated",()->equal(LocalDate.of(2026,11,1),LocalSchedule.next(Instant.parse("2026-10-25T00:45:00Z"),ZoneId.of("Europe/Kyiv"),LocalTime.of(3,30),EnumSet.of(DayOfWeek.SUNDAY)).atZone(ZoneId.of("Europe/Kyiv")).toLocalDate()));
        test("empty weekdays invalid",()->denied(()->LocalSchedule.next(Instant.now(),ZoneId.of("Europe/Kyiv"),LocalTime.NOON,Collections.emptySet())));
        test("past one shot cannot catch up",()->denied(()->LocalSchedule.once(Instant.parse("2026-10-10T10:00:00Z"),Instant.parse("2026-10-10T11:00:00Z"))));
        test("fragmented ELM response single flight",()->{ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(bytes->{equal("ATRV\r",new String(bytes,StandardCharsets.US_ASCII));ch[0].receive("12.".getBytes());ch[0].receive("6V\r>".getBytes());},1000);equal("12.6V",ch[0].send("ATRV",100));});
        test("timeout closes channel and forbids retry",()->{int[] sends={0};ElmChannel ch=new ElmChannel(b->sends[0]++,1000);denied(()->ch.send("ATRV",5));denied(()->ch.send("ATRV",5));equal(1,sends[0]);});
        test("stop closes without any write",()->{int[] sends={0};ElmChannel ch=new ElmChannel(b->sends[0]++,1000);ch.close("Stop");denied(()->ch.send("ATI",10));equal(0,sends[0]);});
        test("unsafe wire command causes zero writes",()->{int[] sends={0};ElmChannel ch=new ElmChannel(b->sends[0]++,1000);denied(()->ch.send("31010000",100));equal(0,sends[0]);});
        test("overflow makes channel terminal",()->{ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->{byte[] flood=new byte[8193];Arrays.fill(flood,(byte)'A');ch[0].receive(flood);},1000);denied(()->ch[0].send("ATI",100));});
        test("multiple prompts rejected",()->{ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->ch[0].receive("OK>>".getBytes()),1000);denied(()->ch[0].send("ATI",100));});
        test("new budget cannot exceed 60 sec",()->denied(()->new ElmChannel(b->{},60_001)));
        test("slow writer cannot bypass reply deadline",()->{ElmChannel[] ch=new ElmChannel[1];ch[0]=new ElmChannel(b->{Thread.sleep(20);ch[0].receive("12.6V>".getBytes());},1000);denied(()->ch[0].send("ATRV",5));});
        test("identity DID allowlist cannot be mutated",()->{int[] did=ReadOnlyPolicy.identityDids();did[0]=0x0000;equal(0xF195,ReadOnlyPolicy.identityDids()[0]);denied(()->configured().checkAndRecord("220000"));});
        test("single BLE probe owner only",()->{ProbeGate gate=new ProbeGate();long id=gate.begin();require(gate.busy());denied(()->gate.begin());require(gate.complete(id));require(!gate.busy());});
        test("stale BLE callback cannot complete newer probe",()->{ProbeGate gate=new ProbeGate();long previous=gate.begin();require(gate.complete(previous));long current=gate.begin();require(!gate.complete(previous));require(gate.busy());require(gate.complete(current));require(!gate.busy());});
        test("invalid BLE ownership token cannot release session",()->{ProbeGate gate=new ProbeGate();long id=gate.begin();require(!gate.complete(0L));require(!gate.complete(-1L));require(gate.busy());require(gate.complete(id));});
        test("known UART table cannot be mutated",()->{UartProfiles.Profile[] p=UartProfiles.known();p[0]=null;require(UartProfiles.known()[0]!=null);});
        test("published UART profiles match exact triples",()->{for(UartProfiles.Profile p:UartProfiles.known())require(UartProfiles.matches(p.service,p.write,p.notify));});
        test("wrong UUID in known UART service blocked",()->{UartProfiles.Profile p=UartProfiles.known()[0];require(!UartProfiles.matches(p.service,p.notify,p.write));});
        test("unknown UART and missing UUID blocked",()->{require(!UartProfiles.matches("unknown","x","y"));require(!UartProfiles.matches(null,null,null));});
        test("passive adapter controls allowed",()->{ReadOnlyPolicy p=new ReadOnlyPolicy();for(String cmd:Arrays.asList("ATMA","ATCAF0","ATCFC0"))p.checkAndRecord(cmd);});
        test("passive parse two CAN frames",()->{CanCapture.Snapshot c=CanCapture.parse("ATMA\r7AA 10 12 62 F1 95 46 31 36\r7AA 8 21 30 38 30 38 31 33 30\r>");equal(2,c.frameCount);equal(1,c.ids.size());equal(2,c.ids.get("7AA").count);});
        test("passive ignores junk without fake frames",()->{CanCapture.Snapshot c=CanCapture.parse("SEARCHING...\rNO DATA\rBUFFER FULL\r>");equal(0,c.frameCount);require(c.adapterBufferFull);});
        test("passive differences are candidate IDs",()->{CanCapture.Snapshot a=CanCapture.parse("123 01 00\n345 02 01"),b=CanCapture.parse("123 01 01\n456 02 02");equal(3,CanCapture.changedIds(a,b).size());});
        test("passive parser rejects malformed payload",()->{CanCapture.Snapshot c=CanCapture.parse("7AA GG 10\n999 01 02\n123 12 Z3\n12 01 02");equal(0,c.frameCount);});
        test("passive monitor stops adapter with CR",()->{ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ch[0]=new ElmChannel(b->{String x=new String(b,StandardCharsets.US_ASCII);writes.add(x);if(x.equals("ATMA\r"))ch[0].receive("123 01 02 03\r".getBytes(StandardCharsets.US_ASCII));},2000);require(ch[0].listenCan(500).contains("123 01 02 03"));equal(Arrays.asList("ATMA\r","\r"),writes);});

        test("streaming passive parses split BLE packets",()->{CanCapture.Stream stream=new CanCapture.Stream();stream.accept("0E9 E2 F1 FF ".getBytes());stream.accept("FF 00 00 00 00\r0E9 11 22\r".getBytes());CanCapture.Snapshot snap=stream.finish();equal(2,snap.frameCount);equal(1,snap.ids.size());});
        test("stream overflow detected before finish",()->{CanCapture.Stream stream=new CanCapture.Stream();stream.accept("0E9 01 02\rBUFFER FULL".getBytes());require(stream.hasAdapterOverflow());});
        test("streaming detects adapter overflow",()->{CanCapture.Stream stream=new CanCapture.Stream();stream.accept("0E9 01 02\rBUFFER FULL\r".getBytes());CanCapture.Snapshot snap=stream.finish();equal(1,snap.frameCount);require(snap.adapterBufferFull);});
        test("streaming limits lines without unbounded growth",()->{CanCapture.Stream stream=new CanCapture.Stream();for(int i=0;i<1700;i++)stream.accept("0E9 01 02\r".getBytes());CanCapture.Snapshot snap=stream.finish();require(snap.truncated);equal(CanCapture.MAX_CAPTURE_LINES,snap.frameCount);});
        test("streaming monitor stops without CAN transmission",()->{ElmChannel[] ch=new ElmChannel[1];List<String> writes=new ArrayList<>();ch[0]=new ElmChannel(b->{String x=new String(b,StandardCharsets.US_ASCII);writes.add(x);if(x.equals("ATMA\r"))ch[0].receive("0E9 01 02\r".getBytes());},2000);equal(1,ch[0].listenCanSnapshot(500).frameCount);equal(Arrays.asList("ATMA\r","\r"),writes);});
        test("PKC sample climate 15 minute fixture",()->equal("3101FD03000F",PkcOfflineCodec.hex(PkcOfflineCodec.climateStartPayload(15))));
        test("PKC sample climate 120 minute fixture",()->equal("3101FD030078",PkcOfflineCodec.hex(PkcOfflineCodec.climateStartPayload(120))));
        test("PKC sample climate stop fixture",()->equal("3101FD030100",PkcOfflineCodec.hex(PkcOfflineCodec.climateStopPayload())));
        test("PKC climate 0 minutes denied",()->denied(()->PkcOfflineCodec.climateStartPayload(0)));
        test("PKC climate 25 minutes denied",()->denied(()->PkcOfflineCodec.climateStartPayload(25)));
        test("PKC climate 121 minutes denied",()->denied(()->PkcOfflineCodec.climateStartPayload(121)));
        test("PKC 22C driver fixture",()->equal("3101FD08002C",PkcOfflineCodec.hex(PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.DRIVER,22))));
        test("PKC 24C passenger fixture",()->equal("3101FD080130",PkcOfflineCodec.hex(PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.PASSENGER,24))));
        test("PKC minimum temperature fixture",()->equal("3101FD080024",PkcOfflineCodec.hex(PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.DRIVER,18))));
        test("PKC maximum temperature fixture",()->equal("3101FD080140",PkcOfflineCodec.hex(PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.PASSENGER,32))));
        test("PKC half-degree temperature fixture",()->equal("3101FD08012D",PkcOfflineCodec.hex(PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.PASSENGER,22.5))));
        test("PKC sub-half-degree temperature denied",()->denied(()->PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.DRIVER,22.2)));
        test("PKC unsafe high temp denied",()->denied(()->PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.DRIVER,33)));
        test("PKC NaN temperature denied",()->denied(()->PkcOfflineCodec.setpointPayload(PkcOfflineCodec.Zone.DRIVER,Double.NaN)));
        test("PKC null zone denied",()->denied(()->PkcOfflineCodec.setpointPayload(null,21)));
        test("PKC VA key hex format valid",()->require(PkcOfflineCodec.looksLikeVaKey("00112233445566778899AABBCCDDEEFF")));
        test("PKC VA key invalid character denied",()->require(!PkcOfflineCodec.looksLikeVaKey("00112233445566778899AABBCCDDEEFZ")));
        test("PKC VIN is not VA key",()->require(!PkcOfflineCodec.looksLikeVaKey("VCF1EBE28PG008912")));
        test("PKC HMAC RFC4231 first case",()->{byte[] key=new byte[20];Arrays.fill(key,(byte)0x0B);equal("B0344C61D8DB38535CA8AFCEAF0BF12B881DC200C9833DA726E9376C2E32CFF7",PkcOfflineCodec.hex(PkcOfflineCodec.hmacSha256(key,"Hi There".getBytes(StandardCharsets.US_ASCII))));});
        test("PKC HMAC differs on changed bytes",()->{byte[] key={1,2,3};require(!Arrays.equals(PkcOfflineCodec.hmacSha256(key,new byte[]{4}),PkcOfflineCodec.hmacSha256(key,new byte[]{5})));});
        test("PKC HMAC missing key denied",()->denied(()->PkcOfflineCodec.hmacSha256(new byte[0],new byte[]{1})));
        test("PKC status setpoint decode",()->equal(22.0,PkcOfflineCodec.interpretStatusField(0x57,44).interpretedValue));
        test("PKC status cabin temp decode",()->equal(20.0,PkcOfflineCodec.interpretStatusField(0x5A,136).interpretedValue));
        test("PKC status ambient temp decode",()->equal(-5.0,PkcOfflineCodec.interpretStatusField(0x5B,86).interpretedValue));
        test("PKC status flags remain undecoded",()->equal("undecodedFlags",PkcOfflineCodec.interpretStatusField(0x5F,0xAB).name));
        test("PKC undocumented status ID denied",()->denied(()->PkcOfflineCodec.interpretStatusField(0x00,0x12)));
        test("PKC out-of-range status byte denied",()->denied(()->PkcOfflineCodec.interpretStatusField(0x57,256)));
        test("PKC cannot transmit to vehicle",()->require(!PkcOfflineCodec.canSendToVehicle()));
        authChecks();
        System.out.println("TOTAL "+count+" checks passed; no vehicle connection or climate command.");
    }
    private static void authChecks() throws Exception {
        byte[] key=new byte[32];Arrays.fill(key,(byte)7);byte[] body="{\"durationMinutes\":20}".getBytes(StandardCharsets.UTF_8);
        String nonce="00112233445566778899aabbccddeeff";long now=1000;
        byte[] signature=SignedEnvelope.sign(key,"POST","/v1/heat-request",now,nonce,body);
        test("valid signed offline envelope",()->require(SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,body,signature,now,(n,e)->true)));
        test("replay blocked atomically",()->{Set<String> used=new HashSet<>();SignedEnvelope.ReplayLedger store=(n,e)->used.add(n);require(SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,body,signature,now,store));require(!SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,body,signature,now,store));});
        test("persistent ledger failure blocks",()->require(!SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,body,signature,now,(n,e)->{throw new Exception("Disk error");})));
        test("missing ledger blocks",()->require(!SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,body,signature,now,null)));
        test("body tampering blocked",()->require(!SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,"{}".getBytes(),signature,now,(n,e)->true)));
        test("stale envelope blocked",()->require(!SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,body,signature,now+31,(n,e)->true)));
        test("future envelope blocked",()->require(!SignedEnvelope.verify(key,"POST","/v1/heat-request",now,nonce,body,signature,now-31,(n,e)->true)));
        test("path tampering blocked",()->require(!SignedEnvelope.verify(key,"POST","/v1/status",now,nonce,body,signature,now,(n,e)->true)));
        test("nonce injection blocked",()->denied(()->SignedEnvelope.sign(key,"POST","/v1/heat-request",now,nonce+"\n",body)));
        test("short pairing key blocked",()->denied(()->SignedEnvelope.sign(new byte[8],"POST","/v1/heat-request",now,nonce,body)));
        test("oversized body blocked",()->denied(()->SignedEnvelope.sign(key,"POST","/v1/heat-request",now,nonce,new byte[4097])));
    }
}
