package com.fiskerwarm.lab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fixed passive tests. No arbitrary terminal, ECU diagnostic or climate transmit UI. */
public final class MainActivity extends Activity {
    private static final int PERMISSIONS=41,EXPORT=42;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final Map<String,ScanResult> found=new LinkedHashMap<>();
    private final List<Button> marks=new ArrayList<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothDevice selected;
    private String selectedName,exportText,filter="—";
    private BleProbe probe;
    private boolean scanning,busy,foreground,stopping,preflightReady;
    private long generation,lastCheckpoint;
    private int runIndex;
    private JSONArray runs=new JSONArray();
    private TextView state,rx,decoded,instructions,log;
    private CheckBox parked;
    private Button connect,control,testA,testB,stop,export,newLog;
    private LinearLayout page;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24,20,24,32);page.setBackgroundColor(Color.rgb(12,25,38));scroll.addView(page);
        label("Fisker Warm · CAN RX",26);label("0.3.5 · ЕТАП 7.4 · ПАСИВНИЙ КОНТРОЛЬ",15);
        label("Автомобільні команди заблоковано. Застосунок сам не вмикає клімат.",16);
        parked=new CheckBox(this);parked.setText("Авто у P, стоянкове гальмо, я біля авто зі штатним ключем");
        parked.setTextColor(Color.WHITE);parked.setTextSize(18);parked.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        parked.setButtonTintList(ColorStateList.valueOf(Color.rgb(135,206,250)));
        parked.setBackgroundColor(Color.rgb(31,57,78));parked.setPadding(16,20,16,20);page.addView(parked);
        state=label("BLE: не підключено\nАдаптер: не перевірено\nФільтр: не встановлено",16);
        rx=label("Прийняті кадри: 0\nОстанній CAN RX на телефоні: —",17);
        decoded=label("Дані з автомобіля ще не отримані.",15);
        LinearLayout connectionRow=row();connect=button(connectionRow,"Підключити Vgate");connect.setOnClickListener(v->scan());
        stop=button(connectionRow,"Зупинити");stop.setOnClickListener(v->stopCurrent());
        control=button(row(),"Контроль CAN RX · 0E9 → 373 → 0E9");control.setOnClickListener(v->confirmControl());
        LinearLayout testsRow=row();testA=button(testsRow,"Тест 0x534\nКлімат On/Off");testA.setOnClickListener(v->confirm(BleProbe.Mode.HVAC_534));
        testB=button(testsRow,"Тест 0x373\nСтатус клімату");testB.setOnClickListener(v->confirm(BleProbe.Mode.HVAC_373));
        instructions=label("Для етапу 7.4 оберіть «Контроль CAN RX». Авто має бути пробуджене. Перемикати клімат під час контролю не потрібно; старі 30-секундні тести повторювати не потрібно.",15);
        LinearLayout hmiRow=row();mark(hmiRow,"Позначити: ON","HMI_ON");mark(hmiRow,"Позначити: OFF","HMI_OFF");
        LinearLayout physicalRow=row();mark(physicalRow,"Є потік повітря","AIRFLOW_PRESENT");mark(physicalRow,"Є тепле повітря","WARM_AIR_PRESENT");
        LinearLayout exportRow=row();export=button(exportRow,"Експортувати журнал");export.setOnClickListener(v->export());
        newLog=button(exportRow,"Новий журнал");newLog.setOnClickListener(v->{
            if(busy)return;
            new AlertDialog.Builder(this).setTitle("Новий журнал").setMessage("Спочатку експортуйте поточний журнал. Почати новий набір вимірювань?")
                    .setPositiveButton("Почати новий",(d,w)->{runs=new JSONArray();persist();append("Новий журнал. Попередні записи не включатимуться до нового експорту.");buttons();})
                    .setNegativeButton("Скасувати",null).show();
        });
        log=label("Журнал подій",12);log.setTextIsSelectable(true);
        label("Нуль кадрів не доводить відсутності сигналу у внутрішній мережі. RX-час — час BLE callback на телефоні, а не апаратна CAN-мітка. Відсутні notifications неможливо однозначно відрізнити від тихої шини.",13);
        setContentView(scroll);load();buttons();
    }
    private TextView label(String s,int size) {TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.rgb(226,237,245));t.setTextSize(size);t.setPadding(2,10,2,12);page.addView(t);return t;}
    private LinearLayout row() {LinearLayout r=new LinearLayout(this);page.addView(r);return r;}
    private Button button(LinearLayout parent,String s) {Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight((int)(52*getResources().getDisplayMetrics().density));b.setTextColor(Color.rgb(8,30,44));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(143,211,246)));parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b;}
    private void mark(LinearLayout parent,String title,String kind) {Button b=button(parent,title);marks.add(b);b.setOnClickListener(v->{try{if(probe!=null)probe.mark(kind);}catch(Exception e){alert("Позначку не збережено",e.getMessage());}});}
    private void append(String text) {if(log!=null){String old=log.getText().toString();if(old.length()>6000)old=old.substring(old.length()-6000);log.setText(old+"\n"+text);}}
    private void alert(String title,String body) {new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Гаразд",null).show();}
    private void buttons() {
        if(connect==null)return;
        connect.setEnabled(!busy);control.setEnabled(!busy && preflightReady);testA.setEnabled(!busy && preflightReady);testB.setEnabled(!busy && preflightReady);
        stop.setEnabled(busy && !stopping);export.setEnabled(!busy && runs.length()>0);newLog.setEnabled(!busy);
        boolean capturing=probe!=null && probe.hvacProgress()[3]==1 && !stopping;
        for(Button b:marks)b.setEnabled(capturing);
        for(Button b:new Button[]{connect,control,testA,testB,stop,export,newLog})b.setAlpha(b.isEnabled()?1f:0.4f);
        for(Button b:marks)b.setAlpha(b.isEnabled()?1f:0.4f);
        if(busy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private boolean readyToStart() {
        if(busy || !foreground)return false;
        if(!parked.isChecked()){alert("Перед тестом","Підтвердьте стоянку у P та присутність біля авто.");return false;}
        if(runs.length()>=8){alert("Журнал заповнений","Експортуйте всі записи, потім натисніть «Новий журнал». Записи не видаляються автоматично.");return false;}
        return true;
    }
    private void confirm(BleProbe.Mode mode) {
        if(!readyToStart() || selected==null || !preflightReady)return;
        new AlertDialog.Builder(this).setTitle("Пасивний запис · 30 секунд")
                .setMessage("Штатно вимкніть клімат. Авто пробуджене, у P, без оновлення ПЗ чи попереджень живлення.\n\nПісля початку відліку: на 5 с увімкніть клімат на екрані авто, на 20 с вимкніть. Кнопки позначок тільки записують ваші дії; команд автомобілю не передають.")
                .setPositiveButton("Почати запис",(d,w)->start(mode)).setNegativeButton("Скасувати",null).show();
    }
    private void confirmControl() {
        if(!readyToStart() || selected==null || !preflightReady)return;
        new AlertDialog.Builder(this).setTitle("Один пасивний контроль CAN RX")
                .setMessage("Авто пробуджене, у P, ви поруч зі штатним ключем. Без оновлення ПЗ чи попереджень живлення.\n\n0E9: до 1 с або 3 кадрів → 373: 3 с → 0E9: до 1 с або 3 кадрів. Один BLE-сеанс. Якщо фільтр дає нуль, перевіряється альтернативний точний CRA для того самого ID; за появи RX повторно перевіряється CM/CF. До 12 с моніторингу загалом.\n\nШирокого моніторингу немає. Кліматом не керуємо, перемикати його не потрібно. «Зупинити» завершує моніторинг через SPACE. За помилки живлення чи стану авто від'єднайте Vgate.")
                .setPositiveButton("Почати пасивний контроль",(d,w)->start(BleProbe.Mode.CAN_RX_CONTROL))
                .setNegativeButton("Скасувати",null).show();
    }
    private void scan() {
        if(!readyToStart())return;
        String[] required=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for(String p:required)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){requestPermissions(required,PERMISSIONS);return;}
        if(adapter==null || !adapter.isEnabled()){alert("Bluetooth","Увімкніть Bluetooth на телефоні.");return;}
        try {
            scanner=adapter.getBluetoothLeScanner();if(scanner==null)throw new IllegalStateException("BLE scanner недоступний");
            found.clear();scanning=true;busy=true;stopping=false;preflightReady=false;buttons();
            state.setText("BLE: пошук vLinker FD-IOS · до 8 секунд\nАдаптер: не перевірено");
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
            ui.postDelayed(endScan,8000);
        }catch(Exception e){endScan(false);alert("Помилка пошуку",e.toString());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] results) {
        super.onRequestPermissionsResult(request,p,results);
        if(request!=PERMISSIONS || !foreground)return;
        for(int r:results)if(r!=PackageManager.PERMISSION_GRANTED){alert("Немає дозволу","Потрібен дозвіл «Пристрої поблизу» для BLE.");return;}
        if(results.length>0)scan();
    }
    private String deviceName(ScanResult r) {String s=r.getScanRecord()==null?null:r.getScanRecord().getDeviceName();return s==null?r.getDevice().getName():s;}
    private final ScanCallback scanCallback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult r) {ui.post(()->{
            if(!scanning)return;String n=deviceName(r);
            if(n!=null && n.toLowerCase(Locale.ROOT).replace(" ","").equals("vlinkerfd-ios") && found.size()<20)found.put(r.getDevice().getAddress(),r);
        });}
        @Override public void onScanFailed(int code){ui.post(()->{endScan(false);state.setText("Помилка BLE scan: "+code);});}
    };
    private final Runnable endScan=()->endScan(true);
    private void endScan(boolean choose) {
        if(scanner!=null && scanning)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
        ui.removeCallbacks(endScan);scanning=false;busy=false;buttons();
        if(!choose || !foreground)return;
        List<ScanResult> list=new ArrayList<>(found.values());
        if(list.isEmpty()){state.setText("vLinker FD-IOS не знайдено. Перевірте живлення й закрийте інші OBD-застосунки; автоматичних повторів немає.");return;}
        String[] titles=new String[list.size()];for(int i=0;i<list.size();i++)titles[i]=deviceName(list.get(i))+" · "+list.get(i).getRssi()+" dBm";
        new AlertDialog.Builder(this).setTitle("Оберіть свій Vgate").setItems(titles,(d,i)->{
            selected=list.get(i).getDevice();selectedName=deviceName(list.get(i));start(BleProbe.Mode.HVAC_PREFLIGHT);
        }).setNegativeButton("Скасувати",null).show();
    }
    private void start(BleProbe.Mode mode) {
        if(!readyToStart() || selected==null)return;
        long token=++generation;busy=true;stopping=false;lastCheckpoint=0;runIndex=runs.length();runs.put(new JSONObject());
        rx.setText("Прийняті кадри: 0\nОстанній CAN RX на телефоні: —");decoded.setText("Новий запис: кадрів ще немає.");
        filter=mode==BleProbe.Mode.HVAC_534?"0x534":mode==BleProbe.Mode.HVAC_373?"0x373":mode==BleProbe.Mode.CAN_RX_CONTROL?"контроль 0E9 / 373":"не встановлено (перевірка адаптера)";
        probe=new BleProbe(this,mode,new BleProbe.Listener(){
            @Override public void log(String text) {append(text);}
            @Override public void done(JSONObject report) {
                if(token!=generation)return;
                try{runs.put(runIndex,report);}catch(Exception e){append("Помилка збереження запису: "+e);}
                String result=report.optString("result");
                if(mode==BleProbe.Mode.HVAC_PREFLIGHT)preflightReady="ADAPTER_SETTINGS_ACKNOWLEDGED".equals(result);
                else if(!report.optBoolean("adapterResetConfirmed"))preflightReady=false;
                JSONObject capture=report.optJSONObject("hvacCapture");
                if(capture!=null && (capture.optBoolean("adapterBufferFull") || capture.optBoolean("unexpectedIdOutsideFilter")
                        || !capture.optBoolean("stopConfirmed")))preflightReady=false;
                if(capture!=null) {
                    int count=capture.optInt("frameCount");JSONArray frames=capture.optJSONArray("frames");String last="—";
                    if(frames!=null && frames.length()>0)last=formatTime(frames.optJSONObject(frames.length()-1).optLong("utcEpochMillis"));
                    rx.setText("Прийняті кадри: "+count+"\nОстанній CAN RX на телефоні: "+last);
                }
                boolean reset=report.optBoolean("adapterResetConfirmed");
                state.setText("BLE: від'єднано\nАдаптер: "+(reset?"скидання підтверджено":"стан не підтверджений — від'єднайте Vgate від OBD")+"\nФільтр запису: "+filter+"\n"+resultLabel(result)+"\n"+report.optString("detail"));
                if(mode==BleProbe.Mode.HVAC_PREFLIGHT && preflightReady)decoded.setText("Адаптер прийняв налаштування. CAN RX ще не перевірений. Оберіть «Контроль CAN RX». Фізична тиша передавача не виміряна.");
                if(mode==BleProbe.Mode.CAN_RX_CONTROL)decoded.setText(report.optString("detail")+"\nЦе перевірка RX, а не запуск HVAC.");
                probe=null;busy=false;stopping=false;buttons();persist();
            }
        });
        state.setText("BLE: підключення\nАдаптер: очікуємо відповіді\nФільтр: "+filter);
        probe.start(selected,selectedName);buttons();ui.removeCallbacks(tick);ui.post(tick);
    }
    private final Runnable tick=new Runnable(){@Override public void run(){
        BleProbe p=probe;if(p==null || !busy)return;long[] s=p.hvacProgress();
        if(s[3]==1) {
            long elapsed=Math.max(0,(System.nanoTime()-s[2])/1_000_000_000L);
            boolean audit=!p.auditPhase().isEmpty();
            state.setText("BLE: "+p.bleState()+"\nФільтр: "+p.activeFilter()+"\nATMA: "+p.atmaWriteEvidence()+"\n"+
                    (audit?p.auditPhase()+" · "+elapsed+" с":"Запис: "+elapsed+" / 30 с")+(stopping?" · зупинка":""));
            rx.setText("Прийняті кадри: "+s[0]+"\nОстанній CAN RX на телефоні: "+(s[1]<0?"—":formatTime(s[1])));
            decoded.setText(p.hvacLatestDescription()+"\nЦе декодування матриці, не підтвердження запуску HVAC.");
            instructions.setText(audit?"Контроль RX виконується автоматично. Не перемикайте клімат. Після завершення експортуйте журнал.":elapsed<5?"Початкова фаза OFF. На 5 с штатно увімкніть клімат.":elapsed<20?
                    "Штатно ON. Позначте виконану дію та реальний потік/тепло. На 20 с штатно вимкніть клімат.":"Штатно OFF. Позначте виконану дію. Дочекайтеся завершення або натисніть «Зупинити».");
            if(System.nanoTime()-lastCheckpoint>5_000_000_000L) {
                lastCheckpoint=System.nanoTime();
                try{JSONObject c=p.checkpoint();if(c!=null){runs.put(runIndex,c);persist();}}catch(Exception e){append("Checkpoint не збережено: "+e);}
            }
        } else state.setText("BLE: "+p.bleState()+"\nАдаптер: налаштування / завершення\nФільтр: "+filter);
        buttons();ui.postDelayed(this,250);
    }};
    private String formatTime(long millis) {return DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));}
    private String resultLabel(String r) {
        if("NO_MATCHING_FRAMES".equals(r))return "Кадрів не отримано";
        if("MATCHING_FRAMES_RECEIVED".equals(r))return "Кадри отримано; потрібен аналіз";
        if("CAPTURE_INCOMPLETE".equals(r))return "Неповний запис — збережіть журнал";
        if("ADAPTER_SETTINGS_ACKNOWLEDGED".equals(r))return "Налаштування адаптера прийнято; CAN RX ще не перевірений";
        if("DISCONNECTED".equals(r) || "BLE_ERROR".equals(r))return "BLE втрачено — запис не підтверджений";
        if("BLOCKED_VOLTAGE".equals(r))return "Тест заблоковано через напругу живлення";
        if("CANCELLED".equals(r))return "Перевірку скасовано";
        return "Перевірка зупинена: "+r;
    }
    private void stopCurrent() {if(scanning){endScan(false);state.setText("Пошук зупинено.");return;}if(probe!=null && !stopping){stopping=true;buttons();probe.cancel();}}
    private File journalFile() {return new File(getFilesDir(),"hvac-stage7-3-current.json");}
    private String bundle() throws Exception {return new JSONObject().put("format","fisker-warm-hvac-bundle-1").put("appVersion","0.3.5-lab")
            .put("androidApi",Build.VERSION.SDK_INT).put("phone",Build.MANUFACTURER+" "+Build.MODEL)
            .put("vehicleClimateStartedByApp",false).put("runs",runs).toString(2);}
    private void persist() {
        try {
            final byte[] bytes=bundle().getBytes(StandardCharsets.UTF_8);
            disk.execute(()->{AtomicFile atomic=new AtomicFile(journalFile());FileOutputStream output=null;
                try{output=atomic.startWrite();output.write(bytes);atomic.finishWrite(output);}
                catch(Exception e){if(output!=null)atomic.failWrite(output);ui.post(()->append("ПОМИЛКА: журнал не збережено на телефоні. Експортуйте його: "+e.getMessage()));}
            });
        }catch(Exception e){append("ПОМИЛКА журналу: "+e);}
    }
    private void load() {
        try {
            AtomicFile file=new AtomicFile(journalFile());if(!file.getBaseFile().exists())return;
            byte[] bytes=file.readFully();if(bytes.length>20_000_000)throw new IllegalStateException("Журнал перевищує ліміт");
            runs=new JSONObject(new String(bytes,StandardCharsets.UTF_8)).getJSONArray("runs");
            append("Відновлено записів: "+runs.length()+". Це архів; живого BLE/CAN RX зараз немає.");
            for(int i=0;i<runs.length();i++)if("RUNNING_CHECKPOINT".equals(runs.optJSONObject(i).optString("result")))append("Є незавершений checkpoint. Дані після нього могли бути втрачені; повний запис не підтверджений.");
        }catch(Exception e){append("Попередній журнал не прочитано: "+e);}
    }
    private void export() {
        if(busy)return;
        try {exportText=bundle();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE,"Fisker-Warm-HVAC-"+Instant.now().toString().replace(':','-')+".json");startActivityForResult(intent,EXPORT);
        }catch(Exception e){alert("Експорт не відкрився",e.toString());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=EXPORT || result!=RESULT_OK || data==null || data.getData()==null || exportText==null)return;
        try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")) {
            if(out==null)throw new IllegalStateException("Файл не відкрито");out.write(exportText.getBytes(StandardCharsets.UTF_8));
            append("JSON-файл збережено. Це не підтверджує видимість сигналів або роботу HVAC.");
        }catch(Exception e){alert("Журнал не експортовано",e.toString());}finally{exportText=null;}
    }
    @Override protected void onResume(){super.onResume();foreground=true;}
    @Override protected void onPause(){foreground=false;if(scanning)endScan(false);if(probe!=null)stopCurrent();super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacks(tick);super.onDestroy();}
}
