package com.fiskerwarm.gateway;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.fiskerwarm.internet.*;
import org.json.*;
import java.util.Random;

public final class GatewayService extends Service {
 private volatile boolean running;private Thread worker;private PowerManager.WakeLock wake;
 static final String PREF="gateway",CHANNEL="gateway-online";private Journal journal;private volatile RelayClient activeClient;
 public static void start(Context c){Intent i=new Intent(c,GatewayService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
 @Override public void onCreate(){super.onCreate();
  NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel(CHANNEL,"Інтернет-шлюз · тільки тест",NotificationManager.IMPORTANCE_LOW));
  Notification note=notification("Підключення… · автомобільні команди заблоковані");
  if(Build.VERSION.SDK_INT>=34)startForeground(6,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(6,note);
  PowerManager p=(PowerManager)getSystemService(POWER_SERVICE);wake=p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FiskerWarm:Gateway");wake.setReferenceCounted(false);
  journal=new Journal(this);
 }
 private Notification notification(String message){Intent i=new Intent(this,GatewayActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Fisker Warm Gateway · ТЕСТ").setContentText(message).setContentIntent(pi).setOngoing(true).build();}
 @Override public int onStartCommand(Intent i,int flags,int id){
  if(!getSharedPreferences(PREF,0).getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY;}
  if(!running){running=true;worker=new Thread(this::loop,"FW-HTTPS-Gateway");worker.start();}return START_STICKY;
 }
 private static long now(){return System.currentTimeMillis()/1000;}
 private void state(String code,String id){
  String safe=code.matches("[A-Z_0-9]{1,64}")?code:"LOCAL_ERROR";String shortId=id!=null&&Wire.hex(id,32)?id.substring(0,8):"";
  android.content.SharedPreferences p=getSharedPreferences(PREF,0);String log=p.getString("log","");String line=now()+" "+safe+" "+shortId+"\n";
  if(log.length()>6000)log=log.substring(log.length()-5000);
  p.edit().putString("state",safe).putLong("stateAt",now()).putString("log",log+line).apply();
 }
 private void outbox(RelayClient client,Config c)throws Exception{
  for(Wire.Receipt r:journal.outbox(c.pair)){
   JSONObject response=client.call(new JSONObject().put("action","receipt").put("receipt",JsonWire.receipt(r)));
   if(!response.optBoolean("recorded"))throw new Exception("ACK_INVALID");journal.ack(r);state(r.stage,r.id);
  }
 }
 private void loop(){int backoff=1;Random jitter=new Random();
  try{
   Config c=Vault.load(this,"gateway");if(c==null)throw new IllegalStateException("NO_CONFIG");
   GatewayEngine engine=new GatewayEngine(c.pair,c.key,journal);int recovered=engine.recover(now());if(recovered>0)state("RECOVERED_NO_RETRY",null);
   RelayClient client=new RelayClient(c);activeClient=client;
   while(running){
    try{
     wake.acquire(65000);outbox(client,c);
     long at=now();String nonce=Wire.random(16);
     JSONObject heartbeat=new JSONObject().put("v",1).put("pair",c.pair).put("at",at).put("nonce",nonce).put("mac",Wire.hmac(c.key,Wire.heartbeatText(c.pair,at,nonce)));
     JSONObject r=client.call(new JSONObject().put("action","poll").put("wait",20).put("heartbeat",heartbeat));
     state("ONLINE",null);backoff=1;
     JSONArray commands=r.getJSONArray("commands");if(commands.length()>1)throw new SecurityException("BATCH_LIMIT");
     for(int k=0;k<commands.length()&&running;k++){
      Wire.Command command=JsonWire.command(commands.getJSONObject(k));GatewayEngine.Received received=engine.receive(command,now());
      // DELIVERED is committed locally before this acknowledgement. No network acknowledgement is treated as vehicle execution.
      try{outbox(client,c);}catch(Exception ignored){}
      if(received.fresh){engine.completeTest(command,now());state("TEST_ACCEPTED_LOCAL",command.id);}else state("DUPLICATE_OR_REJECTED",command.id);
      outbox(client,c);
     }
     if(wake.isHeld())wake.release();Thread.sleep(500);
    }catch(Exception e){
     state(e instanceof SecurityException?"SIGNATURE_OR_JOURNAL_ERROR":RelayClient.error(e),null);
     if(wake.isHeld())wake.release();
     if(e instanceof RelayClient.Failure&&(((RelayClient.Failure)e).status==401||((RelayClient.Failure)e).status==403)){
      getSharedPreferences(PREF,0).edit().putBoolean("enabled",false).commit();running=false;break;
     }
     if(!running)break;Thread.sleep(backoff*1000L+jitter.nextInt(1000));backoff=Math.min(backoff*2,60);
    }
   }
  }catch(Exception e){state("CONFIG_OR_STORAGE_ERROR",null);getSharedPreferences(PREF,0).edit().putBoolean("enabled",false).commit();}
  finally{if(wake!=null&&wake.isHeld())wake.release();running=false;stopSelf();}
 }
 @Override public void onDestroy(){running=false;if(activeClient!=null)activeClient.cancel();if(worker!=null)worker.interrupt();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}
