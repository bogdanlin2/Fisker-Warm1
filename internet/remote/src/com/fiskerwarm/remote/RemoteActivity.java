package com.fiskerwarm.remote;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import com.fiskerwarm.internet.*;
import org.json.*;
import java.util.concurrent.*;

public final class RemoteActivity extends Activity {
 private Config config;private Wire.Command pending;private boolean terminal=true;
 // A slow status request must neither disable sending nor queue a command behind it.
 private final ExecutorService network=Executors.newFixedThreadPool(2),files=Executors.newSingleThreadExecutor();
 private final Handler ui=new Handler(Looper.getMainLooper());
 private boolean visible,destroyed,importing,confirming;private int generation;
 private RelayClient statusRequest,submitRequest;
 private TextView configuration,importFeedback,controlHint,connection,delivery,error;
 private Button send,retry,importButton,clear;

 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout p=Ui.page(this,"Fisker Warm");
  Ui.text(this,p,"Дистанційна перевірка інтернет-доставки. Команда автомобілю не передається.");
  configuration=Ui.text(this,p,"");importFeedback=Ui.text(this,p,"");
  connection=Ui.text(this,p,"Шлюз: стан ще не перевірено");delivery=Ui.text(this,p,"Команду ще не надіслано");
  Ui.text(this,p,"HVAC: запуск заблокований — PKC не підтверджено");error=Ui.text(this,p,"");
  send=Ui.button(this,p,"CLIMATE ON · ТЕСТ",v->authenticate());
  controlHint=Ui.text(this,p,"");retry=Ui.button(this,p,"Повторити передачу того самого ID",v->submitExisting());
  importButton=Ui.button(this,p,"Імпортувати приватний Remote-файл",v->pickConfig());
  Ui.button(this,p,"Оновити стан",v->refresh());
  clear=Ui.button(this,p,"Видалити конфігурацію з телефону",v->clearConfig());
  try{
   config=Vault.load(this,"remote");String c=getPreferences(0).getString("pending",null);
   if(c!=null&&config!=null){pending=JsonWire.command(new JSONObject(c));if(!pending.authentic(config.pair,config.key))throw new Exception();terminal=!pending.fresh(now());}
   if(config!=null&&pending==null)delivery.setText("Готово до тестової передачі.");
  }catch(Exception e){config=null;pending=null;importFeedback.setText("Не вдалося відкрити збережену прив’язку. Імпортуйте Remote-файл повторно.");}
  updateButtons();
 }
 private static long now(){return System.currentTimeMillis()/1000;}
 private RemoteControls.Gate gate(){return RemoteControls.gate(config!=null,importing,confirming,submitRequest!=null,pending,terminal,now());}
 private void updateButtons(){
  RemoteControls.Gate state=gate();
  configuration.setText(config==null?"Прив’язка: Remote-файл ще не імпортовано.":"Прив’язка: Remote-файл збережено на телефоні.");
  send.setEnabled(state==RemoteControls.Gate.READY);retry.setEnabled(RemoteControls.canRetry(state));
  importButton.setEnabled(!importing&&!confirming&&submitRequest==null&&state!=RemoteControls.Gate.WAITING);
  clear.setEnabled(!importing&&!confirming&&submitRequest==null);
  switch(state){
   case NEED_CONFIG:controlHint.setText("Кнопка стане доступною після імпорту приватного Remote-файлу.");break;
   case IMPORTING:controlHint.setText("Читаємо й перевіряємо вибраний файл…");break;
   case CONFIRMING:controlHint.setText("Підтвердьте передачу PIN/паролем телефона.");break;
   case SUBMITTING:controlHint.setText("Передаємо тест до сервера…");break;
   case WAITING:controlHint.setText("Очікуємо результат попереднього тесту до 60 с. Для повтору використовуйте той самий ID.");break;
   default:controlHint.setText("Готово до тестової передачі.");
  }
 }
 @Override public void onResume(){super.onResume();visible=true;ui.post(tick);}
 @Override public void onPause(){visible=false;ui.removeCallbacks(tick);super.onPause();}
 @Override public void onDestroy(){destroyed=true;cancelStatus();if(submitRequest!=null)submitRequest.cancel();network.shutdownNow();files.shutdownNow();super.onDestroy();}
 private final Runnable tick=new Runnable(){public void run(){if(visible){updateButtons();refresh();ui.postDelayed(this,5000);}}};
 private void cancelStatus(){if(statusRequest!=null){statusRequest.cancel();statusRequest=null;}}
 private void clearConfig(){
  if(importing||confirming||submitRequest!=null)return;
  generation++;cancelStatus();Vault.clear(this);getPreferences(0).edit().clear().commit();config=null;pending=null;terminal=true;
  importFeedback.setText("Конфігурацію видалено. Для повного відкликання доступу також відкличте пару на приватній сторінці.");
  connection.setText("Шлюз: потрібна прив’язка");delivery.setText("Команду ще не надіслано");error.setText("");updateButtons();
 }
 private void pickConfig(){
  if(!importButton.isEnabled())return;
  importFeedback.setText("Виберіть Fisker-Warm-remote-private.json у «Завантаження».");
  try{Ui.pick(this);}catch(Exception e){importFeedback.setText("Не вдалося відкрити вибір файлу. Перевірте доступ до «Завантаження» в застосунку «Файли».");}
 }
 private void importConfig(Intent data){
  if(importing)return;
  importing=true;importFeedback.setText("Імпортуємо Remote-файл…");updateButtons();
  files.execute(()->{
   final ConfigImport.Result result=ConfigImport.run(this,data,"remote");
   ui.post(()->{
    if(destroyed)return;
    importing=false;importFeedback.setText(result.message("remote"));
    if(result.config!=null){
     generation++;cancelStatus();config=result.config;pending=null;terminal=true;getPreferences(0).edit().clear().commit();
     delivery.setText("Готово до тестової передачі.");connection.setText("Шлюз: перевіряємо зв’язок…");error.setText("");
    }else if(result.failure==ConfigImport.Failure.STORAGE){generation++;cancelStatus();config=null;pending=null;terminal=true;}
    updateButtons();if(result.config!=null)refresh();
   });
  });
 }
 private void authenticate(){
  if(gate()!=RemoteControls.Gate.READY)return;
  KeyguardManager k=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
  if(k==null||!k.isDeviceSecure()){error.setText("Для авторизації встановіть PIN/пароль блокування S24 Ultra.");return;}
  Intent i=k.createConfirmDeviceCredentialIntent("Fisker Warm · тест","Підтвердьте тестову передачу. Клімат не запускатиметься.");
  if(i==null){error.setText("Авторизація пристрою недоступна.");return;}
  confirming=true;updateButtons();
  try{startActivityForResult(i,42);}catch(Exception e){confirming=false;error.setText("Не вдалося відкрити підтвердження PIN/паролем телефона.");updateButtons();}
 }
 private void createAndSubmit(){
  if(gate()!=RemoteControls.Gate.READY)return;
  try{pending=Wire.command(config.pair,config.key,now());terminal=false;
   if(!getPreferences(0).edit().putString("pending",JsonWire.command(pending).toString()).commit())throw new Exception();
   delivery.setText("ID "+pending.id.substring(0,8)+": передача до сервера…");submitExisting();
  }catch(Exception e){error.setText("Помилка локального журналу — передачу скасовано.");pending=null;terminal=true;updateButtons();}
 }
 private void submitExisting(){
  final Config c=config;final Wire.Command cmd=pending;final int g=generation;
  if(c==null||cmd==null||!cmd.fresh(now())||submitRequest!=null||importing||confirming)return;
  final RelayClient client=new RelayClient(c);submitRequest=client;updateButtons();
  network.execute(()->{
   String failure=null;
   try{JSONObject r=client.call(new JSONObject().put("action","submit").put("command",JsonWire.command(cmd)));if(!r.optBoolean("stored")||!cmd.id.equals(r.optString("id")))throw new Exception();}
   catch(Exception e){failure=RelayClient.error(e);}final String f=failure;
   ui.post(()->{
    if(destroyed||submitRequest!=client)return;submitRequest=null;
    if(g!=generation||cmd!=pending){updateButtons();return;}
    if(f==null){delivery.setText("Сервер зберіг тест. Доставку Gateway ще не підтверджено.");error.setText("");}
    else error.setText("Передача: "+f+". Фактичний результат перевіряється за тим самим ID.");
    updateButtons();refresh();
   });
  });
 }
 private void refresh(){
  final Config c=config;final Wire.Command cmd=pending;final int g=generation;
  if(c==null||statusRequest!=null||importing||destroyed)return;
  final RelayClient client=new RelayClient(c);statusRequest=client;
  network.execute(()->{
   JSONObject result=null;String failure=null;
   try{JSONObject body=new JSONObject().put("action","status");if(cmd!=null)body.put("id",cmd.id);result=client.call(body);}
   catch(Exception e){failure=RelayClient.error(e);}final JSONObject r=result;final String f=failure;
   ui.post(()->{
    if(destroyed||statusRequest!=client)return;statusRequest=null;
    // A response from before an import/new command cannot replace its current state.
    if(g!=generation||cmd!=pending){updateButtons();if(visible)refresh();return;}
    if(f!=null){connection.setText("Шлюз: невідомо — сервер недоступний з цього телефону\n"+f);updateButtons();return;}
    try{
     JSONObject h=r.optJSONObject("heartbeat");boolean online=h!=null&&c.pair.equals(h.optString("pair"))&&Wire.heartbeatValid(c.pair,h.getLong("at"),h.getString("nonce"),h.getString("mac"),c.key,now());
     connection.setText(online?"Шлюз: онлайн · свіжий підпис Gateway":"Шлюз: офлайн / немає свіжого підтвердження");
     JSONObject state=r.optJSONObject("command");
     if(cmd!=null){
      if(state==null){terminal=!cmd.fresh(now());delivery.setText(terminal?"Строк тесту минув. Доставка не підтверджена.":"Gateway ще не підтвердив тест. Очікування до 60 с.");}
      else{
       Wire.Command returned=JsonWire.command(state.getJSONObject("envelope"));if(!returned.authentic(c.pair,c.key)||!returned.mac.equals(cmd.mac)||!returned.id.equals(cmd.id))throw new SecurityException();
       JSONObject receipt=state.optJSONObject("receipt");
       if(receipt==null){terminal=!cmd.fresh(now());delivery.setText(terminal?"Строк тесту минув. Gateway не підтвердив доставку.":"Сервер зберіг тест. Очікуємо Gateway.");}
       else{
        Wire.Receipt ack=JsonWire.receipt(receipt);if(!ack.authentic(cmd,c.key)||ack.at>now()+10)throw new SecurityException();terminal=ack.rank()==2;error.setText("");
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
  if(request==42){confirming=false;updateButtons();if(result==RESULT_OK)createAndSubmit();else error.setText("Тест не надіслано: підтвердження скасовано.");return;}
  if(request==Ui.PICK){if(result==RESULT_OK)importConfig(data);else importFeedback.setText("Імпорт скасовано: файл не вибрано.");}
 }
}
