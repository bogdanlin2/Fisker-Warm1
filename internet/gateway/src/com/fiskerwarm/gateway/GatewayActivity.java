package com.fiskerwarm.gateway;
import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import com.fiskerwarm.internet.*;
import java.util.concurrent.*;

public final class GatewayActivity extends Activity {
 private TextView state,health,ble,log,configuration,importFeedback;private Button start,importButton,clear;
 private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService files=Executors.newSingleThreadExecutor();
 private boolean visible,destroyed,importing,configured;
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout p=Ui.page(this,"Fisker Warm Gateway");
  Ui.text(this,p,"Автомобільні команди заблоковані. Цей шлюз підтверджує лише інтернет-доставку тесту.");
  configuration=Ui.text(this,p,"");importFeedback=Ui.text(this,p,"");state=Ui.text(this,p,"");health=Ui.text(this,p,"");
  start=Ui.button(this,p,"Запустити шлюз",v->startGateway());
  Ui.button(this,p,"Зупинити шлюз",v->{getSharedPreferences(GatewayService.PREF,0).edit().putBoolean("enabled",false).commit();stopService(new Intent(this,GatewayService.class));show();});
  importButton=Ui.button(this,p,"Імпортувати приватний Gateway-файл",v->pickConfig());
  Ui.button(this,p,"Налаштування фонового живлення",v->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){state.setText("Відкрийте налаштування батареї вручну.");}});
  ble=Ui.text(this,p,"BLE: ще не перевірено. Для інтернет-тесту Bluetooth не потрібний.");Ui.button(this,p,"Перевірити підтримку BLE без PKC",v->checkBle());
  clear=Ui.button(this,p,"Видалити конфігурацію з телефону",v->{if(importing)return;getSharedPreferences(GatewayService.PREF,0).edit().putBoolean("enabled",false).commit();stopService(new Intent(this,GatewayService.class));Vault.clear(this);configured=false;importFeedback.setText("Конфігурацію видалено. Відкличте пару на сторінці налаштування для повного скасування доступу.");show();});
  log=Ui.text(this,p,"");log.setTextIsSelectable(true);
  try{configured=Vault.load(this,"gateway")!=null;}catch(Exception e){importFeedback.setText("Не вдалося відкрити збережену прив’язку. Імпортуйте Gateway-файл повторно.");}
  show();
 }
 private void startGateway(){try{if(importing||!configured||Vault.load(this,"gateway")==null){importFeedback.setText("Спочатку імпортуйте Gateway-файл.");return;}
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},51);return;}
  getSharedPreferences(GatewayService.PREF,0).edit().putBoolean("enabled",true).commit();GatewayService.start(this);show();
 }catch(Exception e){importFeedback.setText("Не вдалося запустити шлюз. Перевірте конфігурацію.");}}
 private void checkBle(){if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},52);return;}
  try{BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter a=m==null?null:m.getAdapter();if(a==null||!a.isEnabled()){ble.setText("Увімкніть Bluetooth для перевірки апаратних можливостей.");return;}
   boolean advertising=a.isMultipleAdvertisementSupported()&&a.getBluetoothLeAdvertiser()!=null;BluetoothGattServer server=m.openGattServer(this,new BluetoothGattServerCallback(){});boolean gatt=server!=null;if(server!=null)server.close();
   ble.setText("GATT server: "+(gatt?"відкрито й закрито":"недоступний")+"\nAdvertising API: "+(advertising?"підтримується":"не підтверджено")+"\nСумісність та авторизація PKC цим не перевіряються.");
  }catch(Exception e){ble.setText("Перевірка BLE недоступна. Автомобіль не викликався.");}}
 private void show(){android.content.SharedPreferences p=getSharedPreferences(GatewayService.PREF,0);boolean enabled=p.getBoolean("enabled",false);state.setText("Шлюз: "+(enabled?"увімкнений":"зупинений")+"\nОстання подія: "+p.getString("state","ще немає")+" · "+p.getLong("stateAt",0));
  PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));String battery=b==null?"невідомо":b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)+"% · "+(b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0)+" °C";
  health.setText("Батарея: "+battery+"\nОптимізація Android: "+(power.isIgnoringBatteryOptimizations(getPackageName())?"виняток надано":"може затримати інтернет у сні")+"\nПісля холодного перезапуску потрібне перше розблокування. Після примусової зупинки відкрийте застосунок.");
  log.setText("Журнал без секретів\n"+p.getString("log","")+"\nЖивлення: plugged=0 — батарея, 1 — AC, 2 — USB, 4 — wireless\n"+p.getString("powerLog",""));
  configuration.setText(configured?"Прив’язка: Gateway-файл збережено на телефоні.":"Прив’язка: імпортуйте приватний Gateway-файл.");
  start.setEnabled(!enabled&&configured&&!importing);importButton.setEnabled(!enabled&&!importing);clear.setEnabled(!importing);
 }
 private final Runnable tick=new Runnable(){public void run(){if(visible){show();ui.postDelayed(this,3000);}}};
 @Override public void onResume(){super.onResume();visible=true;ui.post(tick);}
 @Override public void onPause(){visible=false;ui.removeCallbacks(tick);super.onPause();}
 @Override public void onDestroy(){destroyed=true;files.shutdownNow();super.onDestroy();}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(results.length==0)return;if(request==51&&results[0]==PackageManager.PERMISSION_GRANTED)startGateway();else if(request==52){boolean granted=true;for(int r:results)granted&=r==PackageManager.PERMISSION_GRANTED;if(granted)checkBle();else ble.setText("Дозвіл BLE не надано. Інтернет-тест доступний без нього.");}}
 private void pickConfig(){
  if(!importButton.isEnabled())return;
  importFeedback.setText("Виберіть Fisker-Warm-gateway-private.json у «Завантаження».");
  try{Ui.pick(this);}catch(Exception e){importFeedback.setText("Не вдалося відкрити вибір файлу. Перевірте доступ до «Завантаження» в застосунку «Файли».");}
 }
 private void importConfig(Intent data){
  if(importing)return;
  if(getSharedPreferences(GatewayService.PREF,0).getBoolean("enabled",false)){importFeedback.setText("Зупиніть шлюз перед заміною конфігурації.");return;}
  importing=true;importFeedback.setText("Імпортуємо Gateway-файл…");show();
  files.execute(()->{
   final ConfigImport.Result result=ConfigImport.run(this,data,"gateway");
   ui.post(()->{
    if(destroyed)return;importing=false;
    if(result.config!=null)configured=true;else if(result.failure==ConfigImport.Failure.STORAGE)configured=false;
    importFeedback.setText(result.message("gateway")+(result.config!=null?" Натисніть «Запустити шлюз».":""));show();
   });
  });
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(request==Ui.PICK){if(result==RESULT_OK)importConfig(data);else importFeedback.setText("Імпорт скасовано: файл не вибрано.");}
 }
}
