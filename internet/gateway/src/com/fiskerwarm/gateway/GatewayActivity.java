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

public final class GatewayActivity extends Activity {
 private TextView state,health,ble,log;private Button start;private final Handler ui=new Handler(Looper.getMainLooper());private boolean visible;
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout p=Ui.page(this,"Fisker Warm Gateway");
  Ui.text(this,p,"Автомобільні команди заблоковані. Цей шлюз підтверджує лише інтернет-доставку тесту.");state=Ui.text(this,p,"");health=Ui.text(this,p,"");
  start=Ui.button(this,p,"Запустити шлюз",v->startGateway());
  Ui.button(this,p,"Зупинити шлюз",v->{getSharedPreferences(GatewayService.PREF,0).edit().putBoolean("enabled",false).commit();stopService(new Intent(this,GatewayService.class));show();});
  Ui.button(this,p,"Імпортувати приватний Gateway-файл",v->Ui.pick(this));
  Ui.button(this,p,"Налаштування фонового живлення",v->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){state.setText("Відкрийте налаштування батареї вручну.");}});
  ble=Ui.text(this,p,"BLE: ще не перевірено. Для інтернет-тесту Bluetooth не потрібний.");Ui.button(this,p,"Перевірити підтримку BLE без PKC",v->checkBle());
  Ui.button(this,p,"Видалити конфігурацію з телефону",v->{getSharedPreferences(GatewayService.PREF,0).edit().putBoolean("enabled",false).commit();stopService(new Intent(this,GatewayService.class));Vault.clear(this);state.setText("Конфігурацію видалено. Відкличте пару на сторінці налаштування для повного скасування доступу.");});
  log=Ui.text(this,p,"");log.setTextIsSelectable(true);show();
 }
 private void startGateway(){try{if(Vault.load(this,"gateway")==null){state.setText("Спочатку імпортуйте Gateway-файл.");return;}
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},51);return;}
  getSharedPreferences(GatewayService.PREF,0).edit().putBoolean("enabled",true).commit();GatewayService.start(this);show();
 }catch(Exception e){state.setText("Не вдалося запустити шлюз. Перевірте конфігурацію.");}}
 private void checkBle(){if(Build.VERSION.SDK_INT>=31&&(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},52);return;}
  try{BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter a=m==null?null:m.getAdapter();if(a==null||!a.isEnabled()){ble.setText("Увімкніть Bluetooth для перевірки апаратних можливостей.");return;}
   boolean advertising=a.isMultipleAdvertisementSupported()&&a.getBluetoothLeAdvertiser()!=null;BluetoothGattServer server=m.openGattServer(this,new BluetoothGattServerCallback(){});boolean gatt=server!=null;if(server!=null)server.close();
   ble.setText("GATT server: "+(gatt?"відкрито й закрито":"недоступний")+"\nAdvertising API: "+(advertising?"підтримується":"не підтверджено")+"\nСумісність та авторизація PKC цим не перевіряються.");
  }catch(Exception e){ble.setText("Перевірка BLE недоступна. Автомобіль не викликався.");}}
 private void show(){android.content.SharedPreferences p=getSharedPreferences(GatewayService.PREF,0);boolean enabled=p.getBoolean("enabled",false);state.setText("Шлюз: "+(enabled?"увімкнений":"зупинений")+"\nОстання подія: "+p.getString("state","ще немає")+" · "+p.getLong("stateAt",0));
  PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));String battery=b==null?"невідомо":b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)+"% · "+(b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0)+" °C";
  health.setText("Батарея: "+battery+"\nОптимізація Android: "+(power.isIgnoringBatteryOptimizations(getPackageName())?"виняток надано":"може затримати інтернет у сні")+"\nПісля холодного перезапуску потрібне перше розблокування. Після примусової зупинки відкрийте застосунок.");
  log.setText("Журнал без секретів\n"+p.getString("log","")+"\nЖивлення: plugged=0 — батарея, 1 — AC, 2 — USB, 4 — wireless\n"+p.getString("powerLog",""));start.setEnabled(!enabled);
 }
 private final Runnable tick=new Runnable(){public void run(){if(visible){show();ui.postDelayed(this,3000);}}};
 @Override public void onResume(){super.onResume();visible=true;ui.post(tick);}
 @Override public void onPause(){visible=false;ui.removeCallbacks(tick);super.onPause();}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(results.length==0)return;if(request==51&&results[0]==PackageManager.PERMISSION_GRANTED)startGateway();else if(request==52){boolean granted=true;for(int r:results)granted&=r==PackageManager.PERMISSION_GRANTED;if(granted)checkBle();else ble.setText("Дозвіл BLE не надано. Інтернет-тест доступний без нього.");}}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==Ui.PICK&&result==RESULT_OK)try{
  if(getSharedPreferences(GatewayService.PREF,0).getBoolean("enabled",false)){state.setText("Зупиніть шлюз перед заміною конфігурації.");return;}
  String raw=Ui.imported(this,data);new Config(raw,"gateway");Vault.save(this,raw);state.setText("Gateway налаштований. Натисніть «Запустити шлюз».");
 }catch(Exception e){state.setText("Некоректний Gateway-файл. Секрети не показуються.");}}
}
