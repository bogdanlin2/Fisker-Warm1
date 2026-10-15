package com.fiskerwarm.remote;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import com.fiskerwarm.internet.*;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteActivity extends Activity {
 private volatile Config config;private volatile Wire.Command pending;private volatile boolean terminal=true;
 private final ExecutorService network=Executors.newSingleThreadExecutor();private final AtomicBoolean busy=new AtomicBoolean();
 private final Handler ui=new Handler(Looper.getMainLooper());private boolean visible;private int generation;
 private TextView connection,delivery,execution,error;private Button send,retry;
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout p=Ui.page(this,"Fisker Warm");
  Ui.text(this,p,"Дистанційна перевірка інтернет-доставки. Команда автомобілю не передається.");
  connection=Ui.text(this,p,"Шлюз: стан ще не перевірено");delivery=Ui.text(this,p,"Команду ще не надіслано");
  execution=Ui.text(this,p,"HVAC: запуск заблокований — PKC не підтверджено");error=Ui.text(this,p,"");
  send=Ui.button(this,p,"CLIMATE ON · ТЕСТ",v->authenticate());retry=Ui.button(this,p,"Повторити передачу того самого ID",v->submitExisting());
  Ui.button(this,p,"Імпортувати приватний Remote-файл",v->Ui.pick(this));
  Ui.button(this,p,"Оновити стан",v->refresh());
  Ui.button(this,p,"Видалити конфігурацію з телефону",v->{generation++;Vault.clear(this);getPreferences(0).edit().clear().commit();config=null;pending=null;terminal=true;connection.setText("Конфігурацію видалено. Для відкликання доступу також відкличте пару на приватній сторінці налаштування.");updateButtons();});
  try{config=Vault.load(this,"remote");String c=getPreferences(0).getString("pending",null);if(c!=null&&config!=null){pending=JsonWire.command(new JSONObject(c));if(!pending.authentic(config.pair,config.key))throw new Exception();terminal=!pending.fresh(now());}}
  catch(Exception e){config=null;pending=null;error.setText("Не вдалося відкрити конфігурацію. Імпортуйте її повторно.");}
  updateButtons();
 }
 private static long now(){return System.currentTimeMillis()/1000;}
 private void updateButtons(){send.setEnabled(config!=null&&!busy.get()&&(pending==null||terminal||!pending.fresh(now())));retry.setEnabled(config!=null&&pending!=null&&!terminal&&pending.fresh(now())&&!busy.get());}
 @Override public void onResume(){super.onResume();visible=true;ui.post(tick);}
 @Override public void onPause(){visible=false;ui.removeCallbacks(tick);super.onPause();}
 @Override public void onDestroy(){network.shutdownNow();super.onDestroy();}
 private final Runnable tick=new Runnable(){public void run(){if(visible){refresh();ui.postDelayed(this,5000);}}};
 private void authenticate(){
  if(config==null||busy.get())return;
  KeyguardManager k=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
  if(k==null||!k.isDeviceSecure()){error.setText("Для авторизації встановіть PIN/пароль блокування S24 Ultra.");return;}
  Intent i=k.createConfirmDeviceCredentialIntent("Fisker Warm · тест","Підтвердьте тестову передачу. Клімат не запускатиметься.");
  if(i==null){error.setText("Авторизація пристрою недоступна.");return;}startActivityForResult(i,42);
 }
 private void createAndSubmit(){
  try{pending=Wire.command(config.pair,config.key,now());terminal=false;
   if(!getPreferences(0).edit().putString("pending",JsonWire.command(pending).toString()).commit())throw new Exception();
   delivery.setText("ID "+pending.id.substring(0,8)+": передача до сервера…");submitExisting();
  }catch(Exception e){error.setText("Помилка локального журналу — передачу скасовано.");pending=null;terminal=true;updateButtons();}
 }
 private void submitExisting(){
  final Config c=config;final Wire.Command cmd=pending;final int g=generation;
  if(c==null||cmd==null||!cmd.fresh(now())||!busy.compareAndSet(false,true))return;updateButtons();
  network.execute(()->{String failure=null;try{JSONObject r=new RelayClient(c).call(new JSONObject().put("action","submit").put("command",JsonWire.command(cmd)));if(!r.optBoolean("stored")||!cmd.id.equals(r.optString("id")))throw new Exception();}
   catch(Exception e){failure=RelayClient.error(e);}final String f=failure;
   ui.post(()->{busy.set(false);if(g!=generation)return;if(f==null){delivery.setText("Сервер зберіг тест. Доставку Gateway ще не підтверджено.");error.setText("");}else{error.setText("Передача: "+f+". Фактичний результат перевіряється за тим самим ID.");}updateButtons();refresh();});});
 }
 private void refresh(){
  final Config c=config;final Wire.Command cmd=pending;final int g=generation;
  if(c==null||!busy.compareAndSet(false,true))return;updateButtons();
  network.execute(()->{JSONObject result=null;String failure=null;
   try{JSONObject b=new JSONObject().put("action","status");if(cmd!=null)b.put("id",cmd.id);result=new RelayClient(c).call(b);}catch(Exception e){failure=RelayClient.error(e);}
   final JSONObject r=result;final String f=failure;ui.post(()->{busy.set(false);if(g!=generation)return;
    if(f!=null){connection.setText("Шлюз: невідомо — сервер недоступний з цього телефону");error.setText(f);updateButtons();return;}
    try{
     JSONObject h=r.optJSONObject("heartbeat");boolean online=h!=null&&c.pair.equals(h.optString("pair"))&&Wire.heartbeatValid(c.pair,h.getLong("at"),h.getString("nonce"),h.getString("mac"),c.key,now());
     connection.setText(online?"Шлюз: онлайн · свіжий підпис Gateway":"Шлюз: офлайн / немає свіжого підтвердження");error.setText("");
     JSONObject state=r.optJSONObject("command");
     if(cmd!=null){
      if(state==null){terminal=!cmd.fresh(now());delivery.setText(terminal?"Строк тесту минув. Доставка не підтверджена.":"Gateway ще не підтвердив тест. Очікування до 60 с.");}
      else{
       Wire.Command returned=JsonWire.command(state.getJSONObject("envelope"));if(!returned.authentic(c.pair,c.key)||!returned.mac.equals(cmd.mac)||!returned.id.equals(cmd.id))throw new SecurityException();
       JSONObject receipt=state.optJSONObject("receipt");
       if(receipt==null){terminal=!cmd.fresh(now());delivery.setText(terminal?"Строк тесту минув. Gateway не підтвердив доставку.":"Сервер зберіг тест. Очікуємо Gateway.");}
       else{
        Wire.Receipt ack=JsonWire.receipt(receipt);if(!ack.authentic(cmd,c.key)||ack.at>now()+10)throw new SecurityException();terminal=ack.rank()==2;
        if("TEST_ACCEPTED".equals(ack.stage))delivery.setText("Доставлено Gateway · підпис перевірено\nТест прийнято. Автомобіль не викликався.");
        else if("DELIVERED".equals(ack.stage))delivery.setText("Доставлено Gateway · прийняття тесту ще не завершено");
        else if("INTERRUPTED_NO_RETRY".equals(ack.stage))delivery.setText("Доставлено, але шлюз перезапустився. Автоматичний повтор заборонений.");
        else delivery.setText("Gateway відхилив прострочений тест.");
       }
      }
     }
    }catch(Exception e){error.setText("Відповідь не пройшла перевірку. Виконання не підтверджене.");}
    updateButtons();
   });
  });
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(request==42&&result==RESULT_OK){createAndSubmit();return;}
  if(request==Ui.PICK&&result==RESULT_OK)try{String raw=Ui.imported(this,data);Config next=new Config(raw,"remote");Vault.save(this,raw);generation++;config=next;pending=null;terminal=true;getPreferences(0).edit().clear().commit();delivery.setText("Готово до тестової передачі.");error.setText("");updateButtons();refresh();}catch(Exception e){error.setText("Некоректний Remote-файл. Секрети не показуються.");}
 }
}
