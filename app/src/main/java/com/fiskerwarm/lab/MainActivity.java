package com.fiskerwarm.lab;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.fiskerwarm.lab.core.HeatSafety;
import com.fiskerwarm.lab.core.ProbeGate;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.TreeSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@SuppressLint("MissingPermission")
public final class MainActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String,ScanResult> found=new LinkedHashMap<>();
    private final List<Button> probeButtons=new ArrayList<>();
    private final ProbeGate probeGate=new ProbeGate();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BleProbe probe;
    private BleProbe.Mode requestedMode;
    private boolean scanning,busy,foreground;
    private CheckBox parked;
    private TextView status,logView,captureStatus;
    private JSONObject lastReport,baseline;
    private final StringBuilder logBuffer=new StringBuilder();
    private final Runnable endScan=this::showDevices;
    private static final int PERMISSIONS=11,EXPORT=12;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244,244,239));
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22),dp(24),dp(22),dp(26)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()); top=i.top;bottom=i.bottom;
            } else {top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            view.setPadding(0,top,0,bottom);return insets;
        });
        page.addView(text("FISKER WARM / LAB 0.3.3",12,Color.rgb(131,102,42),true));
        TextView heading=text("Перевірка\nOcean.",32,Color.rgb(24,39,53),true);
        heading.setPadding(0,dp(12),0,dp(16)); page.addView(heading);
        page.addView(text("Ocean Extreme · PKC 21.3\nПідготовка до безпечного дистанційного клімату",15,Color.rgb(80,92,97),false));

        LinearLayout heat=card(); page.addView(heat);
        heat.addView(text("ЗАПУСК ПОКИ ЗАБЛОКОВАНО",12,Color.rgb(153,87,22),true));
        heat.addView(text("Знайдено статичний PKC BLE-протокол, але ключ власника, запуск на авто та пробудження замкненого авто не підтверджені.",17,Color.rgb(24,39,53),false));
        Button warm=button("Прогріти зараз — недоступно");warm.setEnabled(false);heat.addView(warm);
        Button explain=button("Що потрібно для запуску");heat.addView(explain);
        explain.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Потрібні підтвердження")
                .setMessage("1. Дозволений власнику протокол PKC 21.3.\n2. Перевірені start, stop і wake.\n3. Відгук, що опалення справді працює.\n4. Свіжі P, швидкість, SOC та 12 В.\n5. Вимкнення в самому авто навіть при збої телефона.\n\nЦя версія не надсилає команд клімату. Таймер і дистанційний шлюз не активовані.")
                .setPositiveButton("Зрозуміло",null).show());

        LinearLayout protocol=card();page.addView(protocol);
        protocol.addView(text("PKC BLE · ОФЛАЙН-ДОСЛІДЖЕННЯ 0.3",12,Color.rgb(93,104,108),true));
        protocol.addView(text("OceanLink SDK: локальні кліматичні payload, 18–32 °C, HMAC-SHA256, статус-коди PKC. Це лише аналіз і офлайн-тести; без цифрового ключа власника, BLE-транспорту та підтвердження роботи на машині.",13,Color.rgb(24,39,53),false));
        protocol.addView(text("PKC GATT-сервер ≠ OBD vLinker. Жодного нового CAN/UDS-запису. START/STOP/TIMER ЗАБЛОКОВАНО.",12,Color.rgb(153,87,22),true));
        LinearLayout known=card();page.addView(known);
        known.addView(text("ПІДТВЕРДЖЕНІ ЗЧИТУВАННЯ · 12.10.2026",11,Color.rgb(93,104,108),true));
        known.addView(text("PKC  F160808130021.3\nECC  ECC395 24\nBCM  BCM395030\nICC   ICC390C49",15,Color.rgb(24,39,53),false));
        known.addView(text("Це історичні версії. Поточні SOC, P, швидкість і стан опалення невідомі.",13,Color.rgb(93,104,108),false));

        LinearLayout checks=card();page.addView(checks);
        checks.addView(text("КОРОТКА ПЕРЕВІРКА БІЛЯ АВТО",12,Color.rgb(93,104,108),true));
        parked=new CheckBox(this);parked.setText("Авто стоїть у P. Я біля нього.");parked.setTextColor(Color.rgb(24,39,53));checks.addView(parked);
        status=text("З'єднання не встановлено.\n12 В: не виміряно. SOC: невідомий.",15,Color.rgb(24,39,53),false);checks.addView(status);
        addProbeButton(checks,"Виміряти напругу через vLinker",BleProbe.Mode.VOLTAGE_ONLY);
        addProbeButton(checks,"Прочитати PKC / ECC / BCM / ICC",BleProbe.Mode.IDENTITIES);
        addProbeButton(checks,"Оглянути BLE GATT без записів",BleProbe.Mode.GATT_MAP);
        Button stop=button("Зупинити й роз'єднати");checks.addView(stop);
        stop.setOnClickListener(v->{
            stopScan();
            if(probe!=null) probe.cancel();
            else setBusy(false);
            // Do not release UI busy until the cancelled BLE probe reports done.
        });
        checks.addView(text("Сесія обмежена 60 секундами, огляд GATT — 15. Низька або невідома напруга зупиняє читання блоків. Застосунок працює лише на відкритому екрані.",13,Color.rgb(93,104,108),false));

        LinearLayout climate=card();page.addView(climate);
        climate.addView(text("CLIMATE MONITOR · ПАСИВНИЙ CAN",12,Color.rgb(93,104,108),true));
        climate.addView(text("Два короткі записи по 0,5 секунди: спочатку клімат ВИМКНЕНО, потім увімкни його ШТАТНО з екрана авто та повтори. Завершення запису може зайняти трохи довше. Програма не вмикає опалення і не надсилає діагностичних запитів у цьому режимі.",14,Color.rgb(24,39,53),false));
        captureStatus=text("Ще немає записів CAN.",13,Color.rgb(80,92,97),false);climate.addView(captureStatus);
        addProbeButton(climate,"1. Записати CAN · клімат ВИМКНЕНО",BleProbe.Mode.PASSIVE_CAN_OFF);
        addProbeButton(climate,"2. Записати CAN · клімат УВІМКНЕНО",BleProbe.Mode.PASSIVE_CAN_ON);
        climate.addView(text("Різниця CAN ID — лише кандидат на дослідження, а НЕ підтвердження роботи обігріву. Якщо шлюз не пропускає CAN-трафік, буде 0 кадрів. Під час запису телефон поруч з авто, екран відкритий.",12,Color.rgb(93,104,108),false));

        LinearLayout history=card();page.addView(history);
        history.addView(text("ЖУРНАЛ ПЕРЕВІРКИ",12,Color.rgb(93,104,108),true));
        logView=text("Нової перевірки ще немає.",12,Color.rgb(52,65,76),false);logView.setTypeface(Typeface.MONOSPACE);logView.setTextIsSelectable(true);history.addView(logView);
        Button export=button("Зберегти результат у JSON");history.addView(export);export.setOnClickListener(v->export());
        page.addView(text("Без інтернет-доступу, фонових опитувань і підписок.\nДіагностична збірка CAN 0.3.3 потребує контрольного тесту на Ocean. Прогрів заблокований до підтвердження протоколу.",12,Color.rgb(93,104,108),false));
        setContentView(scroll);loadLast();loadBaseline();
    }
    private void addProbeButton(LinearLayout parent,String title,BleProbe.Mode mode) {
        Button b=button(title);parent.addView(b);probeButtons.add(b);
        b.setOnClickListener(v->{if(!parked.isChecked()){message("Перед перевіркою","Підтвердь, що автомобіль на стоянці у P і ти біля нього.");return;}
            if(mode==BleProbe.Mode.PASSIVE_CAN_ON && !freshBaseline()) {
                message("Спершу еталонний запис","Зроби новий запис із вимкненим кліматом (крок 1), не давніший за 15 хвилин.");return;
            }
            requestedMode=mode;requestScan();});
    }
    private void requestScan() {
        if(busy || probeGate.busy() || !foreground)return;
        String[] required=Build.VERSION.SDK_INT>=31?
                new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for(String p:required)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){requestPermissions(required,PERMISSIONS);return;}
        if(adapter==null || !adapter.isEnabled()){message("Bluetooth","Увімкни Bluetooth і повтори перевірку.");return;}
        try {
            scanner=adapter.getBluetoothLeScanner();if(scanner==null)throw new IllegalStateException("No BLE scanner");
            found.clear();scanning=true;setBusy(true);status.setText("Пошук BLE · до 10 секунд…");
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
            ui.postDelayed(endScan,10_000);
        } catch(Exception e){stopScan();setBusy(false);message("Пошук не вдався",e.toString());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=PERMISSIONS || !foreground)return;
        if(results.length==0){message("Немає дозволу","Пошук скасовано.");return;}
        for(int r:results)if(r!=PackageManager.PERMISSION_GRANTED){message("Немає дозволу","Дозволь доступ до пристроїв поблизу для перевірки BLE.");return;}
        requestScan();
    }
    private final ScanCallback scanCallback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) {ui.post(()->{
            if(scanning && found.size()<100)found.put(result.getDevice().getAddress(),result);
        });}
        @Override public void onScanFailed(int code){ui.post(()->{stopScan();setBusy(false);status.setText("Помилка BLE scan: "+code);});}
    };
    private String name(ScanResult r) {
        String n=r.getScanRecord()==null?null:r.getScanRecord().getDeviceName();
        if(n==null)n=r.getDevice().getName();return n;
    }
    private void showDevices() {
        if(!scanning)return;stopScan();setBusy(false);
        List<ScanResult> devices=new ArrayList<>();
        for(ScanResult r:found.values()) {
            String n=name(r);
            if(requestedMode==BleProbe.Mode.GATT_MAP || (n!=null && n.toLowerCase(Locale.ROOT).replace(" ","").startsWith("vlinkerfd")))devices.add(r);
        }
        if(devices.isEmpty()){status.setText("Пристрій не знайдено. Це не доводить, що Ocean спить.");message("Не знайдено","Розбуди авто штатним ключем, перевір живлення адаптера й закрий інші діагностичні застосунки. Не повторюй пошук циклічно.");return;}
        String[] labels=new String[devices.size()];
        for(int i=0;i<devices.size();i++){ScanResult r=devices.get(i);String n=name(r);labels[i]=(n==null?"Без назви":n)+"\n"+r.getDevice().getAddress()+" · "+r.getRssi()+" dBm";}
        new AlertDialog.Builder(this).setTitle(requestedMode==BleProbe.Mode.GATT_MAP?"Лише твій адаптер або PKC":"Обери свій vLinker FD+")
                .setItems(labels,(dialog,index)->{
                    ScanResult r=devices.get(index);
                    if(requestedMode==BleProbe.Mode.GATT_MAP)new AlertDialog.Builder(this).setTitle("Огляд власного пристрою")
                            .setMessage("Обирай тільки пристрій свого Ocean або vLinker. Буде прочитана структура GATT; значення характеристик і ключі не читаються. Жодних записів чи сполучення.")
                            .setPositiveButton("Оглянути",(d,w)->startProbe(r)).setNegativeButton("Скасувати",null).show();
                    else startProbe(r);
                }).setNegativeButton("Скасувати",null).show();
    }
    private void startProbe(ScanResult r) {
        if(busy || !parked.isChecked())return;
        long token;
        try { token=probeGate.begin(); }
        catch(IllegalStateException e){message("Зачекай", "Попередня перевірка ще завершується.");return;}
        setBusy(true);logBuffer.setLength(0);logView.setText("");status.setText("Коротка перевірка…");
        probe=new BleProbe(this,requestedMode,new BleProbe.Listener(){
            @Override public void log(String text){appendLog(text);}
            @Override public void done(JSONObject report){
                if(!probeGate.complete(token))return; // An old callback must not own this UI.
                if(report.optString("mode").equals(BleProbe.Mode.PASSIVE_CAN_OFF.toString())) {
                    if(validCanReport(report)) {
                        baseline=report;
                        try{Files.write(baselineFile().toPath(),baseline.toString(2).getBytes(StandardCharsets.UTF_8));}
                        catch(Exception e){appendLog("Не зберегли контрольний запис: "+e.getMessage());}
                        captureStatus.setText("Контрольний запис збережено: "+frames(report)+" кадрів. Увімкни клімат ШТАТНО та виконай крок 2.");
                    }
                } else if(report.optString("mode").equals(BleProbe.Mode.PASSIVE_CAN_ON.toString())) {
                    if(validCanReport(report) && freshBaseline() && baseline.optString("deviceAddress").equals(report.optString("deviceAddress"))) {
                        try {
                            report.put("baseline",baseline);
                            JSONObject comparison=compareCan(baseline,report);
                            report.put("comparison",comparison);
                            captureStatus.setText("Порівняння: "+comparison.optString("summary")+"\nЦе не підтвердження стану HVAC.");
                        }catch(Exception e){appendLog("Порівняння неможливе: "+e.getMessage());}
                    }else captureStatus.setText("Другий запис є, але порівняння неможливе: перевір час, пристрій і результат.");
                }
                lastReport=report;probe=null;setBusy(false);
                String voltage=report.isNull("voltageAtObd")?"не виміряно":report.optString("voltageAtObd")+" В";
                status.setText("Перевірка завершена · BLE роз'єднано.\nНапруга на OBD: "+voltage+"\nSOC, P і стан опалення: невідомі.\nРезультат: "+report.optString("result"));
                try{Files.write(cacheFile().toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));}catch(Exception e){appendLog("Не вдалося зберегти журнал: "+e.getMessage());}
            }
        });probe.start(r.getDevice(),name(r));
    }
    private void appendLog(String s) {
        logBuffer.append(s).append('\n');if(logBuffer.length()>16_000)logBuffer.delete(0,logBuffer.length()-16_000);
        logView.setText(logBuffer.toString());
    }
    private void stopScan() {
        ui.removeCallbacks(endScan);scanning=false;
        try{if(scanner!=null)scanner.stopScan(scanCallback);}catch(Exception ignored){}scanner=null;
    }
    private void setBusy(boolean value){busy=value;for(Button b:probeButtons)b.setEnabled(!value);parked.setEnabled(!value);}
    @Override protected void onStart(){super.onStart();foreground=true;}
    @Override protected void onStop(){
        foreground=false;stopScan();
        if(probe!=null) probe.cancel();
        else setBusy(false);
        super.onStop();
    }
    private File cacheFile(){return new File(getFilesDir(),"last-probe.json");}
    private void loadLast() {
        try{File f=cacheFile();if(!f.isFile() || f.length()>524_288)return;
            lastReport=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            appendLog("Збережений історичний результат: "+lastReport.optString("finishedAt")+" · "+lastReport.optString("result"));
            status.setText("Є попередній журнал. Поточний стан авто невідомий.");
        }catch(Exception ignored){}
    }
    private File baselineFile(){return new File(getFilesDir(),"can-climate-off.json");}
    private void loadBaseline(){
        try {
            File f=baselineFile();if(!f.isFile() || f.length()>524288)return;
            baseline=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            if(freshBaseline())captureStatus.setText("Є свіжий контрольний запис: "+frames(baseline)+" кадрів. Тепер зроби крок 2.");
            else captureStatus.setText("Попередній контрольний запис застарів. Почни з кроку 1.");
        }catch(Exception ignored){}
    }
    private boolean validCanReport(JSONObject r){
        return r!=null && ("PASSIVE_CAPTURED".equals(r.optString("result")) || "NO_PASSIVE_FRAMES".equals(r.optString("result")));
    }
    private int frames(JSONObject r){return r==null?0:r.optJSONObject("passiveCan")==null?0:r.optJSONObject("passiveCan").optInt("frameCount",0);}
    private boolean freshBaseline(){
        if(!validCanReport(baseline))return false;
        try {long age=java.time.Duration.between(Instant.parse(baseline.getString("finishedAt")),Instant.now()).toMillis();
            return age>=0 && age<=15*60*1000;
        }catch(Exception ex){return false;}
    }
    private static Map<String,Set<String>> signatures(JSONObject r){
        Map<String,Set<String>> m=new HashMap<>();JSONObject can=r.optJSONObject("passiveCan");
        JSONArray ids=can==null?null:can.optJSONArray("ids");if(ids==null)return m;
        for(int i=0;i<ids.length();i++){
            JSONObject row=ids.optJSONObject(i);if(row==null)continue;
            String id=row.optString("id");if(!id.matches("[0-7][0-9A-Fa-f]{2}"))continue;
            JSONArray samples=row.optJSONArray("samples");Set<String> ss=new HashSet<>();
            if(samples!=null)for(int j=0;j<samples.length();j++)ss.add(samples.optString(j));
            m.put(id,ss);
        }return m;
    }
    private static JSONObject compareCan(JSONObject first,JSONObject second)throws Exception {
        JSONObject output=new JSONObject();
        int a=first.optJSONObject("passiveCan").optInt("frameCount"), b=second.optJSONObject("passiveCan").optInt("frameCount");
        output.put("beforeFrames",a).put("afterFrames",b);
        Map<String,Set<String>> before=signatures(first),after=signatures(second);
        JSONArray changed=new JSONArray();TreeSet<String> keys=new TreeSet<>();keys.addAll(before.keySet());keys.addAll(after.keySet());
        for(String id:keys)if(!before.containsKey(id) || !after.containsKey(id) || !before.get(id).equals(after.get(id)))changed.put(id);
        output.put("changedCandidateIds",changed).put("beforeUniqueIds",before.size()).put("afterUniqueIds",after.size());
        boolean incomplete=first.optJSONObject("passiveCan").optBoolean("adapterBufferFull") || second.optJSONObject("passiveCan").optBoolean("adapterBufferFull")
                || first.optJSONObject("passiveCan").optBoolean("truncated") || second.optJSONObject("passiveCan").optBoolean("truncated")
                || !first.optJSONObject("passiveCan").optBoolean("stopConfirmed") || !second.optJSONObject("passiveCan").optBoolean("stopConfirmed")
                || "INCOMPLETE".equals(first.optJSONObject("passiveCan").optString("captureQuality"))
                || "INCOMPLETE".equals(second.optJSONObject("passiveCan").optString("captureQuality"));
        boolean sameAdapter=first.optString("deviceAddress").equals(second.optString("deviceAddress"));
        boolean phasesMatch="OFF".equals(first.optString("observationPhase")) && "ON".equals(second.optString("observationPhase"));
        boolean usable=a>0 && b>0 && !incomplete && sameAdapter && phasesMatch;
        output.put("sameAdapter",sameAdapter).put("phasesMatch",phasesMatch);
        output.put("captureIncomplete",incomplete);
        output.put("usable",usable).put("climateStateVerified",false).put("decodedSignals",0);
        output.put("summary",usable?("CAN IDs зі змінними даними: "+changed.length()+" · "+a+" → "+b+" кадрів"):
                (incomplete?"Неповний запис CAN (переповнення або обрізання): ":"Немає достатніх CAN-кадрів для порівняння: ")+a+" / "+b);
        output.put("warning","CAN differences may come from counters, timing or other systems; NOT confirmed climate signals");
        return output;
    }
    private void export() {
        if(busy){message("Перевірка триває","Спочатку дочекайся завершення або зупини перевірку.");return;}
        if(lastReport==null){message("Поки немає результату","Виконай коротку перевірку або огляд GATT.");return;}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,"fisker-warm-lab-"+Instant.now().toString().replace(":", "-")+".json");startActivityForResult(intent,EXPORT);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=EXPORT || result!=RESULT_OK || data==null || data.getData()==null || lastReport==null)return;
        try(OutputStream out=getContentResolver().openOutputStream(data.getData())) {
            if(out==null)throw new IllegalStateException("No export stream");out.write(lastReport.toString(2).getBytes(StandardCharsets.UTF_8));
            message("Збережено","JSON містить фактичні відповіді цієї перевірки. Він не містить команди прогріву.");
        }catch(Exception e){message("Помилка збереження",e.toString());}
    }
    private void message(String title,String body){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Гаразд",null).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String body,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(body);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(7),0,dp(7));if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(12),dp(16),dp(16));l.setBackgroundColor(Color.WHITE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(18);l.setLayoutParams(p);return l;}
    private Button button(String title){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);b.setLayoutParams(p);return b;}
}
