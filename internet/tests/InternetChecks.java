import com.fiskerwarm.internet.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
public final class InternetChecks {
 static int count;static final String PAIR="0123456789abcdef0123456789abcdef";
 static final byte[] KEY=Wire.decode("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
 static void ok(boolean b,String m){count++;if(!b)throw new AssertionError(m);}
 static void blocked(Runnable f,String m){try{f.run();throw new AssertionError(m);}catch(SecurityException|IllegalStateException expected){count++;}}
 // Independent disk-backed Journal implementation tests the state machine through process recreation.
 static final class DiskJournal implements GatewayEngine.Journal {
  final Path path;final Map<String,Wire.Command> commands=new HashMap<>();final Map<String,Wire.Receipt> receipts=new HashMap<>();
  DiskJournal(Path p)throws Exception{path=p;if(Files.exists(p))try(DataInputStream d=new DataInputStream(Files.newInputStream(p))){int n=d.readInt();while(n-->0){Wire.Command c=new Wire.Command(d.readUTF(),d.readUTF(),d.readLong(),d.readLong(),d.readUTF(),d.readUTF(),d.readUTF());Wire.Receipt r=new Wire.Receipt(d.readUTF(),d.readUTF(),d.readUTF(),d.readLong(),d.readUTF(),d.readUTF(),d.readUTF());commands.put(c.id,c);receipts.put(c.id,r);}}}
  void save(){try{Path tmp=path.resolveSibling("journal.tmp");try(DataOutputStream d=new DataOutputStream(Files.newOutputStream(tmp))){d.writeInt(commands.size());for(Wire.Command c:commands.values()){d.writeUTF(c.pair);d.writeUTF(c.id);d.writeLong(c.issued);d.writeLong(c.expires);d.writeUTF(c.type);d.writeUTF(c.mode);d.writeUTF(c.mac);Wire.Receipt r=receipts.get(c.id);d.writeUTF(r.pair);d.writeUTF(r.id);d.writeUTF(r.commandMac);d.writeLong(r.at);d.writeUTF(r.stage);d.writeUTF(r.vehicle);d.writeUTF(r.mac);}}Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new IllegalStateException("DISK");}}
  public Wire.Receipt find(String p,String id){return receipts.get(id);}
  public boolean insert(Wire.Command c,Wire.Receipt r){if(receipts.containsKey(c.id))return false;commands.put(c.id,c);receipts.put(c.id,r);save();return true;}
  public void replace(Wire.Receipt r){receipts.put(r.id,r);save();}
  public List<Wire.Command> interrupted(String p){List<Wire.Command> out=new ArrayList<>();for(Wire.Command c:commands.values())if(receipts.get(c.id).rank()==1)out.add(c);return out;}
 }
 public static void main(String[] args)throws Exception{
  if(args.length>0&&args[0].equals("vector")){Wire.Command c=new Wire.Command(PAIR,"11111111111111111111111111111111",1700000000L,1700000060L,Wire.TYPE,Wire.MODE,"");System.out.println(Wire.hmac(KEY,c.text()));return;}
  long t=1700000000L;Wire.Command c=Wire.command(PAIR,KEY,t);
  ok(c.authentic(PAIR,KEY),"signed command");ok(c.fresh(t+59),"TTL inclusive start");ok(!c.fresh(t+60),"expired boundary");ok(!c.fresh(t-11),"future clock skew");
  ok(!c.authentic("22222222222222222222222222222222",KEY),"pair isolation");byte[] bad=KEY.clone();bad[0]++;ok(!c.authentic(PAIR,bad),"wrong owner key");
  Wire.Command altered=new Wire.Command(PAIR,c.id,t,t+60,"CLIMATE_ON",Wire.MODE,c.mac);ok(!altered.authentic(PAIR,KEY),"real vehicle verb denied");
  altered=new Wire.Command(PAIR,c.id,t,t+3600,Wire.TYPE,Wire.MODE,c.mac);ok(!altered.authentic(PAIR,KEY),"expiry tamper");
  Path dir=Files.createTempDirectory("fw-stage6-");Path file=dir.resolve("journal.bin");DiskJournal store=new DiskJournal(file);GatewayEngine engine=new GatewayEngine(PAIR,KEY,store);
  GatewayEngine.Received first=engine.receive(c,t);ok(first.fresh&&first.receipt.stage.equals("DELIVERED"),"durable delivery before acceptance");
  ok(first.receipt.authentic(c,KEY),"delivery signed by gateway");ok(!engine.receive(c,t+1).fresh,"duplicate not new work");
  Wire.Receipt done=engine.completeTest(c,t+1);ok(done.stage.equals("TEST_ACCEPTED")&&done.vehicle.equals("NOT_IMPLEMENTED"),"test separate from HVAC");ok(done.authentic(c,KEY),"acceptance signed");
  store=new DiskJournal(file);engine=new GatewayEngine(PAIR,KEY,store);ok(engine.recover(t+100)==0,"completed receipt not recovered as new");
  GatewayEngine.Received replay=engine.receive(c,t+100);ok(!replay.fresh&&replay.receipt.mac.equals(done.mac),"restart and expired replay returns same result");
  Wire.Command modified=new Wire.Command(PAIR,c.id,t+2,t+62,Wire.TYPE,Wire.MODE,"");modified=new Wire.Command(PAIR,c.id,t+2,t+62,Wire.TYPE,Wire.MODE,Wire.hmac(KEY,modified.text()));
  final Wire.Command conflict=modified;final GatewayEngine current=engine;blocked(()->current.receive(conflict,t+3),"ID conflict");
  Wire.Command stale=Wire.command(PAIR,KEY,t+200);ok(engine.receive(stale,t+261).receipt.stage.equals("EXPIRED_REJECTED"),"offline stale command rejected");
  Wire.Command crash=Wire.command(PAIR,KEY,t+300);engine.receive(crash,t+300);store=new DiskJournal(file);engine=new GatewayEngine(PAIR,KEY,store);
  ok(engine.recover(t+301)==1,"restart detects incomplete work");ok(engine.receive(crash,t+302).receipt.stage.equals("INTERRUPTED_NO_RETRY"),"never rerun ambiguous work");ok(engine.completeTest(crash,t+303).stage.equals("INTERRUPTED_NO_RETRY"),"recovery terminal");
  ok(!new GatewayEngine.BlockedPkcPort().canSendToVehicle(),"PKC is blocked");
  String nonce="33333333333333333333333333333333";String hm=Wire.hmac(KEY,Wire.heartbeatText(PAIR,t,nonce));
  ok(Wire.heartbeatValid(PAIR,t,nonce,hm,KEY,t+75),"signed online presence");ok(!Wire.heartbeatValid(PAIR,t,nonce,hm,KEY,t+76),"stale presence offline");ok(!Wire.heartbeatValid(PAIR,t,nonce,hm,bad,t),"forged presence rejected");
  Wire.Receipt fake=new Wire.Receipt(done.pair,done.id,done.commandMac,done.at,done.stage,"HVAC_ON",done.mac);ok(!fake.authentic(c,KEY),"vehicle result spoof rejected");
  final GatewayEngine.Journal failing=new GatewayEngine.Journal(){public Wire.Receipt find(String p,String id){return null;}public boolean insert(Wire.Command x,Wire.Receipt r){return false;}public void replace(Wire.Receipt r){}public List<Wire.Command> interrupted(String p){return Collections.emptyList();}};
  final GatewayEngine failed=new GatewayEngine(PAIR,KEY,failing);blocked(()->failed.receive(Wire.command(PAIR,KEY,t),t),"failed durable commit must fail closed");
  Files.delete(file);Files.delete(dir);System.out.println("Internet core checks passed: "+count);
 }
}
