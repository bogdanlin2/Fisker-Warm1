package com.fiskerwarm.lab;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.fiskerwarm.lab.core.ElmChannel;
import com.fiskerwarm.lab.core.CanCapture;
import com.fiskerwarm.lab.core.ElmParser;
import com.fiskerwarm.lab.core.HeatSafety;
import com.fiskerwarm.lab.core.ReadOnlyPolicy;
import com.fiskerwarm.lab.core.UartProfiles;
import com.fiskerwarm.lab.core.HvacJournal;
import com.fiskerwarm.lab.core.PassiveNetworkPlan;
import com.fiskerwarm.lab.core.GattWriteGate;
import com.fiskerwarm.lab.core.BleIoJournal;
import com.fiskerwarm.lab.core.CanRxAudit;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground, finite probes only. GATT-map mode never writes characteristics or descriptors. */
@SuppressLint("MissingPermission")
public final class BleProbe {
    public enum Mode { VOLTAGE_ONLY, IDENTITIES, GATT_MAP, PASSIVE_CAN_OFF, PASSIVE_CAN_ON, CAN_NETWORK_AUDIT,
        HVAC_PREFLIGHT, HVAC_534, HVAC_373, CAN_RX_CONTROL }
    public interface Listener { void log(String text); void done(JSONObject report); }
    private static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final Mode mode;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean terminal=new AtomicBoolean(false), started=new AtomicBoolean(false);
    private final JSONObject report=new JSONObject();
    private final JSONArray events=new JSONArray();
    private final HvacJournal ownerMarks=new HvacJournal();
    private final BleIoJournal ioJournal=new BleIoJournal();
    private final AtomicBoolean ioOverrun=new AtomicBoolean(false);
    private final GattWriteGate writeGate=new GattWriteGate((kind,id,value,status)->{
        recordIo(kind,System.nanoTime(),id,value,status,"2AF1", "WRITE_CALLBACK_IS_NOT_CAN_RX");
        if(java.util.Arrays.equals(value,"ATMA\r".getBytes(StandardCharsets.US_ASCII))) {
            atmaWriteEvidence="TX_COMPLETE".equals(kind)?"GATT_SUCCESS · це не CAN RX":kind;
        }
    });
    private volatile String atmaWriteEvidence="ще не записано",activeFilter="—",auditPhase="",auditWarning="";
    private volatile BluetoothGatt gatt;
    private volatile ElmChannel channel;
    private volatile BluetoothGattCharacteristic writeChar;
    private volatile BluetoothGattCharacteristic notifyChar;
    private volatile boolean linkConnected;
    private final AtomicBoolean mtuPending=new AtomicBoolean(false), subscriptionStarted=new AtomicBoolean(false);
    private final JSONObject transport=new JSONObject();
    private volatile int effectiveMtu=23;
    private final long startElapsed=SystemClock.elapsedRealtime();
    private final Runnable watchdog=()->finish("SESSION_LIMIT", "Hard session deadline reached");
    private final Runnable mtuWatchdog=()->{
        if(mtuPending.compareAndSet(true,false))finish("MTU_TIMEOUT","MTU operation did not complete; no overlapping GATT writes started");
    };

    public BleProbe(Context context,Mode mode,Listener listener) {
        this.context=context.getApplicationContext(); this.mode=mode; this.listener=listener;
    }
    private boolean isHvac() {return mode==Mode.HVAC_PREFLIGHT || mode==Mode.HVAC_534 || mode==Mode.HVAC_373 || mode==Mode.CAN_RX_CONTROL;}
    public String atmaWriteEvidence(){return atmaWriteEvidence;}
    public String activeFilter(){return activeFilter;}
    public String auditPhase(){return auditPhase;}
    public String auditWarning(){return auditWarning;}
    public long[] hvacProgress() {ElmChannel ch=channel;return ch==null?new long[]{0,-1,-1,0}:ch.hvacProgress();}
    public String hvacLatestDescription() {ElmChannel ch=channel;return ch==null?"Кадрів немає":ch.hvacLatestDescription();}
    public String bleState() {return terminal.get()?"від'єднано":linkConnected?"з'єднано":"підключення / перевірка GATT";}
    public void mark(String kind) {
        if(!isHvac() || terminal.get() || hvacProgress()[3]!=1)throw new IllegalStateException("Немає активного запису");
        ownerMarks.mark(kind,System.nanoTime(),System.currentTimeMillis());log("Позначка власника: "+kind);
    }
    /** A running checkpoint is explicitly incomplete; it never fabricates frames. */
    public JSONObject checkpoint() throws Exception {
        ElmChannel ch=channel;
        if(ch==null || hvacProgress()[3]!=1)return null;
        synchronized(report) {
            return new JSONObject(report.toString()).put("hvacCapture",new JSONObject(ch.hvacCaptureJson()))
                    .put("bleIo",new JSONObject(ioJournal.json()))
                    .put("ownerMarks",new JSONArray(ownerMarks.marksJson())).put("result","RUNNING_CHECKPOINT")
                    .put("incomplete",true).put("checkpointAt",Instant.now().toString());
        }
    }
    public void start(BluetoothDevice device,String advertisedName) {
        try {
            report.put("format",isHvac()?"fisker-warm-hvac-passive-1":"fisker-warm-lab-1"); report.put("appVersion","0.3.6-lab");
            report.put("diagnosticRevision","can-burst-stage7.5-1").put("bleTransport",transport);
            report.put("mode",mode.toString()); report.put("startedAt",Instant.now().toString());
            report.put("heatCapability",HeatSafety.BUILD_STATUS); report.put("events",events);
            report.put("deviceName",advertisedName); report.put("deviceAddress",device.getAddress());
            report.put("hvSoc",JSONObject.NULL); report.put("gear",JSONObject.NULL);
            report.put("speedKph",JSONObject.NULL); report.put("climateRunning",JSONObject.NULL);
            if(isHvac()) {
                report.put("vehicleCommandsSent",0).put("canTransmissionsRequested",0).put("syntheticRx",false)
                        .put("physicalCanRxTimestampsAvailable",false).put("physicalSilentModeVerified",false)
                        .put("matrixSourceCommit",com.fiskerwarm.lab.core.HvacDecoder.SOURCE_COMMIT)
                        .put("adapterResetConfirmed",false).put("noAutomaticReconnect",true)
                        .put("receiptMeaning","RX only from adapter notifications; cannot independently detect dropped notifications or verify on-wire silence");
                if(advertisedName==null || !advertisedName.toLowerCase(Locale.ROOT).replace(" ","").equals("vlinkerfd-ios")) {
                    finish("BLOCKED_ADAPTER_PROFILE","Потрібен перевірений профіль vLinker FD-IOS; жодного AT не надіслано");return;
                }
            }
            if(mode!=Mode.GATT_MAP && (advertisedName==null ||
                    !advertisedName.toLowerCase(Locale.ROOT).replace(" ","").startsWith("vlinkerfd"))) {
                finish("BLOCKED_DEVICE","Only a named vLinker FD adapter can receive AT/UDS commands"); return;
            }
            ui.postDelayed(watchdog,mode==Mode.GATT_MAP?15_000:ReadOnlyPolicy.MAX_SESSION_MILLIS);
            log("Opening a single BLE connection; automatic reconnect is off.");
            BluetoothGatt opening=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            gatt=opening;
            if(opening==null) finish("CONNECT_ERROR","Android returned no GATT connection");
            else if(terminal.get()) {
                // A very fast callback may have finished before connectGatt returned.
                try { opening.disconnect(); } catch(Exception ignored) {}
                try { opening.close(); } catch(Exception ignored) {}
                gatt=null;
            }
        } catch(Exception e) { finish("CONNECT_ERROR",e.toString()); }
    }
    public void cancel() {
        ElmChannel ch=channel;
        if(isHvac() && ch!=null && ch.requestMonitorStop()) {log("Зупинка запису; очікуємо prompt адаптера…");return;}
        finish("CANCELLED","User left the foreground or stopped the probe");
    }

    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
            if(terminal.get()) { g.close(); return; }
            if(gatt==null)gatt=g;
            if(g!=gatt)return;
            stateEvent("CONNECTION_STATE",status+" / "+state);
            if(status!=BluetoothGatt.GATT_SUCCESS) { finish("BLE_ERROR","Connection status "+status); return; }
            if(state==BluetoothProfile.STATE_CONNECTED) {
                linkConnected=true;
                log("BLE connected. This does not prove the vehicle is awake.");
                if(!g.discoverServices()) finish("GATT_ERROR","Cannot start service discovery");
            } else if(state==BluetoothProfile.STATE_DISCONNECTED) finish("DISCONNECTED","Peripheral disconnected");
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) {
            if(terminal.get() || g!=gatt) return;
            stateEvent("SERVICES_DISCOVERED",Integer.toString(status));
            if(status!=BluetoothGatt.GATT_SUCCESS) { finish("GATT_ERROR","Discovery status "+status); return; }
            try {
                JSONArray services=new JSONArray();
                for(BluetoothGattService s:g.getServices()) {
                    JSONObject row=new JSONObject().put("uuid",s.getUuid().toString());
                    JSONArray chars=new JSONArray();
                    for(BluetoothGattCharacteristic c:s.getCharacteristics()) {
                        JSONArray desc=new JSONArray();
                        for(BluetoothGattDescriptor d:c.getDescriptors()) desc.put(d.getUuid().toString());
                        chars.put(new JSONObject().put("uuid",c.getUuid().toString()).put("properties",c.getProperties()).put("descriptors",desc));
                    }
                    row.put("characteristics",chars); services.put(row);
                }
                report.put("gatt",services);
                if(mode==Mode.GATT_MAP) {
                    log("GATT structure captured; no value reads, subscriptions, pairing or writes.");
                    finish("GATT_MAP_CAPTURED","This is a map, not a verified climate protocol"); return;
                }
                List<BluetoothGattCharacteristic[]> pairs=new ArrayList<>();
                for(UartProfiles.Profile profile:UartProfiles.known()) {
                    // Exact UART triple observed on the owner's FD-IOS in the 15 Oct export.
                    if(isHvac() && !profile.service.equals("000018f0-0000-1000-8000-00805f9b34fb"))continue;
                    BluetoothGattService s=g.getService(UUID.fromString(profile.service)); if(s==null) continue;
                    BluetoothGattCharacteristic w=s.getCharacteristic(UUID.fromString(profile.write));
                    BluetoothGattCharacteristic n=s.getCharacteristic(UUID.fromString(profile.notify));
                    if(w==null || n==null) continue;
                    if((w.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))==0) continue;
                    if((n.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))==0) continue;
                    pairs.add(new BluetoothGattCharacteristic[]{w,n});
                }
                if(pairs.size()!=1) { finish("UART_NOT_CONFIRMED","Missing or ambiguous known UART service; export a GATT map first"); return; }
                writeChar=pairs.get(0)[0]; BluetoothGattCharacteristic notify=pairs.get(0)[1]; notifyChar=notify;
                report.put("writeUuid",writeChar.getUuid().toString()).put("notifyUuid",notify.getUuid().toString());
                long remaining=ReadOnlyPolicy.MAX_SESSION_MILLIS-(SystemClock.elapsedRealtime()-startElapsed);
                if(remaining<=0) {finish("SESSION_LIMIT","Session expired before UART setup");return;}
                channel=mode==Mode.CAN_RX_CONTROL?ElmChannel.forCanRxAudit(BleProbe.this::write,remaining):
                        isHvac()?ElmChannel.forHvac(BleProbe.this::write,remaining):new ElmChannel(BleProbe.this::write,remaining);
                transport.put("writeType",(writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?"WITH_RESPONSE":"NO_RESPONSE")
                        .put("writeCompletionMeaning","Host GATT callback; not ELM semantic acknowledgment or CAN RX");
                if(isHvac() || mode==Mode.PASSIVE_CAN_OFF || mode==Mode.PASSIVE_CAN_ON || mode==Mode.CAN_NETWORK_AUDIT) {
                    transport.put("highPriorityRequestQueued",g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH));
                    transport.put("highPriorityConfirmed",false).put("requestedMtu",247).put("negotiatedMtu",JSONObject.NULL);
                    mtuPending.set(true);ui.postDelayed(mtuWatchdog,2500);
                    if(!g.requestMtu(247) && mtuPending.compareAndSet(true,false)) {
                        ui.removeCallbacks(mtuWatchdog);transport.put("mtuRequestQueued",false);
                        subscribe(g);
                    } else transport.put("mtuRequestQueued",true);
                } else {
                    subscribe(g);
                }
            } catch(Exception e) { finish("GATT_ERROR",e.toString()); }
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) {
            if(terminal.get() || g!=gatt || !CCC.equals(d.getUuid()) || !sameCharacteristic(d.getCharacteristic(),notifyChar)) return;
            if(status!=BluetoothGatt.GATT_SUCCESS) { finish("NOTIFY_ERROR","Subscription status "+status); return; }
            stateEvent("NOTIFICATIONS_SUBSCRIBED",Integer.toString(status));
            if(started.compareAndSet(false,true)) worker.execute(BleProbe.this::read);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) {
            boolean accepted=g==gatt && sameCharacteristic(c,writeChar) && !terminal.get();
            if(!accepted){recordIo("IGNORED_WRITE_CALLBACK",System.nanoTime(),0,null,status,endpoint(c),"WRONG_ENDPOINT_OR_CLOSED");return;}
            writeGate.complete(status);
        }
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status) {
            if(g!=gatt || terminal.get() || !mtuPending.compareAndSet(true,false))return;
            ui.removeCallbacks(mtuWatchdog);
            try {
                transport.put("mtuGattStatus",status);
                if(status==BluetoothGatt.GATT_SUCCESS && mtu>=23) {
                    effectiveMtu=mtu;transport.put("negotiatedMtu",mtu);
                }
                // Failed negotiation keeps small commands within the default 20-byte payload.
                subscribe(g);
            } catch(Exception e) {finish("GATT_ERROR",e.toString());}
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
            long callbackEntry=System.nanoTime();
            ElmChannel ch=channel;
            String route=value==null?"NULL_VALUE":terminal.get()?"CLOSED":g!=gatt?"OTHER_GATT":
                    !sameCharacteristic(c,notifyChar)?"OTHER_ENDPOINT":ch==null?"NO_ELM_CHANNEL":"ELM_CHANNEL";
            // Raw bytes are recorded BEFORE endpoint checks and BEFORE the ELM/parser state gate.
            recordIo("RX_NOTIFICATION",callbackEntry,0,value,-1,endpoint(c),route);
            if("ELM_CHANNEL".equals(route) && !ioOverrun.get())ch.receiveAdapterNotification(value.clone(),callbackEntry);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
            if(Build.VERSION.SDK_INT<33) { byte[] v=c.getValue(); if(v!=null) onCharacteristicChanged(g,c,v); }
        }
    };

    private static String endpoint(BluetoothGattCharacteristic c) {
        return c==null?"null":(c.getService()==null?"no-service":c.getService().getUuid()+"#"+c.getService().getInstanceId())+
                "/"+c.getUuid()+"#"+c.getInstanceId();
    }
    private void recordIo(String kind,long nanos,long id,byte[] bytes,int status,String endpoint,String route) {
        if(!isHvac())return;
        if(!ioJournal.add(kind,nanos,id,bytes,status,endpoint,route) && ioOverrun.compareAndSet(false,true))
            ui.post(()->finish("BLE_JOURNAL_FULL","Сирий BLE-журнал заповнений; вимірювання припинене, prefix збережено"));
    }

    private static boolean sameCharacteristic(BluetoothGattCharacteristic a,BluetoothGattCharacteristic b) {
        return a!=null && b!=null && a.getService()!=null && b.getService()!=null
                && UartProfiles.sameEndpoint(a.getService().getUuid().toString(),a.getService().getInstanceId(),a.getUuid().toString(),a.getInstanceId(),
                b.getService().getUuid().toString(),b.getService().getInstanceId(),b.getUuid().toString(),b.getInstanceId());
    }
    private void subscribe(BluetoothGatt g) throws Exception {
        if(terminal.get() || g!=gatt || !subscriptionStarted.compareAndSet(false,true))return;
        BluetoothGattCharacteristic notify=notifyChar;
        if(!g.setCharacteristicNotification(notify,true)) {finish("NOTIFY_ERROR","Local subscription failed");return;}
        BluetoothGattDescriptor d=notify.getDescriptor(CCC);
        if(d==null) {finish("NOTIFY_ERROR","No CCC descriptor");return;}
        boolean useNotifications=(notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0;
        transport.put("receiveMode",useNotifications?"NOTIFICATION":"INDICATION");
        byte[] value=useNotifications?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        if(Build.VERSION.SDK_INT>=33) {
            if(g.writeDescriptor(d,value)!=0)finish("NOTIFY_ERROR","Descriptor write did not start");
        } else {d.setValue(value);if(!g.writeDescriptor(d))finish("NOTIFY_ERROR","Descriptor write did not start");}
    }

    private void write(byte[] bytes) throws Exception {
        if(terminal.get() || gatt==null || writeChar==null) throw new IllegalStateException("No active UART");
        if(ioOverrun.get())throw new IllegalStateException("Raw BLE journal full");
        if(isHvac() && !PassiveNetworkPlan.hvacWireBytesAllowed(bytes,mode==Mode.CAN_RX_CONTROL))
            throw new SecurityException("Non-passive wire bytes blocked");
        if(bytes.length>effectiveMtu-3)throw new IllegalArgumentException("UART command exceeds negotiated ATT payload");
        int type=(writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        writeGate.write(bytes,3000,value->{
            if(terminal.get() || gatt==null)throw new IllegalStateException("Connection closed before queue");
            if(Build.VERSION.SDK_INT>=33)return gatt.writeCharacteristic(writeChar,value,type);
            writeChar.setWriteType(type);writeChar.setValue(value);return gatt.writeCharacteristic(writeChar)?0:-1;
        });
        if(ioOverrun.get())throw new IllegalStateException("Raw BLE journal incomplete");
    }
    private String command(String cmd,long timeout) throws Exception {
        if(terminal.get()) throw new IllegalStateException("Stopped");
        log("TX "+cmd);
        String r=channel.send(cmd,timeout);
        synchronized(report) { events.put(new JSONObject().put("at",Instant.now().toString()).put("command",cmd).put("reply",r)); }
        log("RX "+r.replace('\r',' ').replace('\n',' ')); return r;
    }
    private void expectOk(String cmd) throws Exception {
        String r=command(cmd,2500); if(!"OK".equals(r.trim())) throw new IllegalStateException("Налаштування не підтверджено: "+cmd+" → "+r);
    }
    private void read() {
        try {
            String banner=command("ATZ",4000);
            if(!banner.toUpperCase(Locale.ROOT).contains("ELM")) throw new IllegalStateException("Adapter identity not confirmed");
            expectOk("ATE0"); String identity=command("ATI",2500);
            if(!identity.toUpperCase(Locale.ROOT).contains("ELM")) throw new IllegalStateException("Unexpected adapter identity");
            report.put("adapterIdentity",identity);
            Double voltage=ElmParser.voltage(command("ATRV",2500));
            report.put("voltageAtObd",voltage==null?JSONObject.NULL:voltage).put("voltageMeasuredAt",Instant.now().toString());
            report.put("voltageMeaning","Adapter supply at OBD; not battery SOC, IBS data or battery health");
            if(mode==Mode.VOLTAGE_ONLY) {
                finish(voltage==null?"VOLTAGE_UNVERIFIED":"VOLTAGE_CAPTURED","No UDS requests were transmitted; this is not ECU or sleep status"); return;
            }
            if(!ReadOnlyPolicy.diagnosticVoltageAcceptable(voltage)) {
                finish("BLOCKED_VOLTAGE","No UDS: voltage unknown, below 12.2 V or outside expected range"); return;
            }
            if(isHvac()) {hvacCapture();return;}
            if(mode==Mode.CAN_NETWORK_AUDIT) {networkAudit();return;}
            if(mode==Mode.PASSIVE_CAN_OFF || mode==Mode.PASSIVE_CAN_ON) {
                for(String cmd:new String[]{"ATL0","ATS1","ATH1","ATSP6","ATCAF0","ATCFC0"}) expectOk(cmd);
                String stage=mode==Mode.PASSIVE_CAN_OFF?"OFF":"ON";
                report.put("observationPhase",stage);
                report.put("phaseMeaning","User-reported HVAC state, not verified by car");
                report.put("captureSeconds",0.5);
                report.put("captureConfig",new JSONObject().put("protocolCommand","ATSP6").put("canIdBits",11).put("canBitrate",500000)
                        .put("customFilterCommands",new JSONArray()).put("hardwareFiltersVerified",false)
                        .put("meaning","Requested 11-bit/500kbps OBD monitor; not proof of HVAC bus visibility or adapter filter defaults"));
                log("Listening to CAN for 0.5 seconds; no UDS requests or CAN frames transmitted.");
                CanCapture.Snapshot snap=channel.listenCanSnapshot(500);
                JSONObject can=snapshotJson(snap);report.put("passiveCan",can);
                log("Passive frames: "+snap.frameCount+" · CAN IDs: "+snap.ids.size());
                if(snap.incomplete())
                    log("Incomplete capture; compare only as exploratory data.");
                finish(snap.frameCount==0?"NO_PASSIVE_FRAMES":"PASSIVE_CAPTURED",
                        snap.frameCount==0?"OBD gateway may not forward broadcast CAN traffic":"Read-only passive snapshot; climate state is NOT decoded");return;
            }
            for(String cmd:new String[]{"ATL0","ATS1","ATH1","ATSP6","ATCAF1","ATAL","ATCFC1"}) expectOk(cmd);
            JSONArray readings=new JSONArray(); report.put("identities",readings);
            for(ReadOnlyPolicy.Route route:ReadOnlyPolicy.routes()) {
                expectOk("ATSH"+route.tx); expectOk("ATCRA"+route.rx); expectOk("ATFCSH"+route.tx);
                for(int did:ReadOnlyPolicy.identityDids()) {
                    String raw=command(String.format(Locale.ROOT,"22%04X",did),4000);
                    JSONObject row=new JSONObject().put("ecu",route.name).put("tx",route.tx).put("rx",route.rx)
                            .put("did",String.format(Locale.ROOT,"%04X",did)).put("at",Instant.now().toString()).put("raw",raw);
                    try {
                        byte[] value=ElmParser.identityFramed(raw,route.rx,did);
                        row.put("status","read").put("ascii",ElmParser.printable(value)).put("hex",ElmParser.hexText(value));
                        readings.put(row);
                    } catch(IllegalArgumentException invalid) {
                        row.put("status","unverified").put("reason",invalid.getMessage()); readings.put(row);
                        finish("STOPPED_UNVERIFIED_REPLY",invalid.getMessage()+"; no retry and no further ECU requests"); return;
                    }
                }
            }
            finish("IDENTITIES_CAPTURED","Completed a bounded read-only snapshot");
        } catch(Exception e) { finish("PROBE_ERROR",e.toString()); }
    }
    private void hvacCapture() throws Exception {
        for(String cmd:new String[]{"ATL0","ATS1","ATH1","ATSP6","ATCAF0","ATCFC0","ATCSM1","ATD1"})expectOk(cmd);
        report.put("silentModeCommandReply","OK").put("silentModeEvidence","ACTUAL_ADAPTER_ACKNOWLEDGMENT_ONLY_NOT_ELECTRICAL_VERIFICATION");
        if(!"6".equals(command("ATDPN",2500).trim()))throw new IllegalStateException("Потрібен підтверджений налаштований протокол 6");
        if(mode==Mode.HVAC_PREFLIGHT) {
            resetAdapter();finish("ADAPTER_SETTINGS_ACKNOWLEDGED","Налаштування прийнято; CAN RX і фізична тиша ще не перевірені");return;
        }
        if(mode==Mode.CAN_RX_CONTROL){canRxControl();return;}
        String id=mode==Mode.HVAC_534?"534":"373";
        activeFilter="0x"+id;
        expectOk("ATAR");expectOk("ATCM7FF");expectOk("ATCF"+id);
        report.put("exactReceiveFilter",id).put("requestedWindowMillis",30_000)
                .put("hardwareFilterBehaviorVerified",false);
        log("Запис 0x"+id+" · 30 секунд. На 5 с — штатно ON, на 20 с — штатно OFF. Позначайте виконані дії.");
        CanCapture.Snapshot capture=channel.listenCanSnapshot(30_000);
        report.put("hardwareFilterBehaviorVerified",false); // No matching RX cannot prove a mask; even matching RX is bounded evidence.
        if(capture.stopConfirmed)resetAdapter();
        String result=capture.incomplete()?"CAPTURE_INCOMPLETE":capture.frameCount==0?"NO_MATCHING_FRAMES":"MATCHING_FRAMES_RECEIVED";
        finish(result,capture.incomplete()?"Запис неповний: "+capture.endReason:
                capture.frameCount==0?"Кадрів немає. Видимість через OBD не підтверджена; це не доказ відсутності сигналу.":
                "Отримані кадри адаптера. Матриця й робота HVAC на авто потребують аналізу.");
    }
    private void canRxControl() throws Exception {
        final JSONArray phases=new JSONArray();report.put("canRxAudit",phases).put("incomplete",true).put("differentialPhasesCompleted",false);
        CanRxAudit.Result result=new CanRxAudit(channel,new CanRxAudit.Observer(){
            @Override public void phase(String name,String id,String method){
                auditPhase=name;activeFilter="0x"+id+" · "+method;atmaWriteEvidence="очікуємо запис ATMA";
                log("Контроль RX: "+name+" · "+activeFilter);
            }
            @Override public void captured(CanRxAudit.Phase phase){
                if("0E9".equals(phase.id) && phase.capture.incomplete()) {
                    auditWarning="Контроль 0E9 НЕПОВНИЙ: "+(phase.capture.adapterBufferFull?"BUFFER FULL; ":"")+phase.capture.endReason+
                            "; пошкоджених рядків: "+phase.capture.invalidFrameLines+". Ознака RX: "+phase.controlRxObserved()+". Збережіть журнал.";
                    log(auditWarning);
                }
                try{synchronized(report){phases.put(new JSONObject().put("phase",phase.name).put("id",phase.id).put("filterMethod",phase.method)
                        .put("controlRxObserved",phase.controlRxObserved()).put("captureComplete",!phase.capture.incomplete())
                        .put("controlOverflowRecoveryAllowed",CanRxAudit.controlRecoveryAllowed(phase.capture))
                        .put("capture",new JSONObject(HvacJournal.captureJson(phase.capture))));}}
                catch(Exception e){throw new IllegalStateException(e);}
            }
            @Override public void recovery(String state){
                auditPhase="Відновлення адаптера · "+state;activeFilter="ще не встановлено після reset";
                stateEvent("CONTROL_OVERFLOW_RECOVERY",state);log(auditPhase);
            }
        }).run();
        if(!result.phases.isEmpty() && result.phases.get(result.phases.size()-1).capture.stopConfirmed)resetAdapter();
        report.put("canRxAuditResult",result.code);
        report.put("incomplete",!result.allCapturesComplete() || !result.differentialPhasesCompleted).put("differentialPhasesCompleted",result.differentialPhasesCompleted);
        report.put("canRxAuditEvidence",new JSONObject().put("allCapturesComplete",result.allCapturesComplete())
                .put("controlStreamIncomplete",result.controlStreamIncomplete()).put("controlBeforeRxObserved",result.beforeRxObserved())
                .put("controlAdapterBufferFull",result.controlHasOverflow())
                .put("controlAfterRxObserved",result.afterRxObserved()).put("eccRxObserved",result.eccRxObserved())
                .put("eccCapturesComplete",result.eccCapturesComplete()).put("recoveriesAttempted",result.recoveriesAttempted)
                .put("recoveriesCompleted",result.recoveriesCompleted).put("presenceMeaning","Matching complete adapter lines; distinct physical transmissions and freshness are not independently proven"));
        String base=result.code.replace("_WITH_CONTROL_LOSS","");
        String detail="CONTROL_RX_PRESENT_ECC_NOT_OBSERVED".equals(base)?
                "CAN RX 0E9 підтверджено до й після; 373 не спостерігався ні через CM/CF, ні через CRA.":
                "ECC_RX_OBSERVED_WITH_CONTROLS".equals(base)?"CAN-рядки 373 і контроль 0E9 отримано від адаптера; потрібен аналіз журналу.":
                "NO_CONTROL_RX".equals(base)?"Контроль 0E9 не отримано через CM/CF та CRA. Видимість HVAC оцінити не можна.":
                "FILTER_API_DIFFERENCE_OBSERVED".equals(base)?"0E9 видно через CRA, але не через CM/CF до й після. Виявлена різниця API фільтрів.":
                "ECC_RX_ONLY_VIA_CRA_OBSERVED".equals(base)?"373 видно через CRA, але не через CM/CF до й після. Потрібен аналіз різниці API фільтрів.":
                "Контроль не дав однозначного результату: "+result.code+". Збережіть повний журнал.";
        if(result.controlStreamIncomplete())detail+=" Контрольні потоки НЕПОВНІ"+(result.controlHasOverflow()?" (BUFFER FULL)":"")+". Це не безвтратне вимірювання і не HVAC ON.";
        finish(result.code,detail);
    }
    private void resetAdapter() throws Exception {
        String reset=command("ATZ",4000);
        if(!reset.toUpperCase(Locale.ROOT).contains("ELM"))throw new IllegalStateException("Reset адаптера не підтверджений");
        report.put("adapterResetConfirmed",true);log("Налаштування адаптера скинуто; автомобіль не скидався.");
    }
    private String diagnosticCommand(String command) throws Exception {
        if(terminal.get())throw new IllegalStateException("Stopped");
        log("Adapter RX/status "+command);
        String reply=channel.sendAdapterDiagnostic(command,2500);
        synchronized(report) {events.put(new JSONObject().put("at",Instant.now().toString()).put("command",command).put("reply",reply).put("scope","ADAPTER_RECEIVE_OR_STATUS_ONLY"));}
        return reply;
    }
    private void diagnosticOk(String command) throws Exception {
        String reply=diagnosticCommand(command);
        if(!"OK".equals(reply.trim()))throw new IllegalStateException("Unsupported/unacknowledged adapter setting "+command+": "+reply);
    }
    private void networkAudit() throws Exception {
        JSONObject audit=new JSONObject().put("observedFloodId","0E9").put("requestedIdBits",11)
                .put("windowMillisPerGroup",PassiveNetworkPlan.WINDOW_MILLIS).put("groupCount",11)
                .put("plannedIdsOtherThanFlood",2047).put("vehicleCommandsSent",0)
                .put("climateStateVerified",false).put("decodedSignals",0).put("heatingAvailable",false);
        JSONArray groups=new JSONArray();audit.put("groups",groups);report.put("networkAudit",audit);
        for(String cmd:new String[]{"ATL0","ATS1","ATH1","ATSP6","ATCAF0","ATCFC0"})expectOk(cmd);
        diagnosticOk("ATCSM1"); // Explicit silent monitoring. CSM0 is never permitted.
        String protocol=diagnosticCommand("ATDPN");audit.put("protocolReply",protocol);
        if(!"6".equals(protocol.trim()))throw new IllegalStateException("Network audit requires confirmed configured protocol 6");
        boolean dlcAcknowledged="OK".equals(diagnosticCommand("ATD1").trim());
        audit.put("dlcDisplayAcknowledged",dlcAcknowledged);
        audit.put("canStatusBefore",diagnosticCommand("ATCS"));
        diagnosticOk("ATCM000");diagnosticOk("ATCF000");
        log("Short unfiltered reference, then all 2047 other 11-bit IDs; no ECU requests.");
        CanCapture.Snapshot reference=channel.listenCanSnapshot(PassiveNetworkPlan.WINDOW_MILLIS,true);
        audit.put("reference",snapshotJson(reference));
        if(!reference.stopConfirmed || reference.truncated)throw new IllegalStateException("Reference monitor did not finish cleanly; no further operations");
        audit.put("canStatusAfterReference",diagnosticCommand("ATCS"));
        boolean otherIdSeen=false, complete=true;
        for(PassiveNetworkPlan.Group group:PassiveNetworkPlan.groups()) {
            diagnosticOk(group.maskCommand());diagnosticOk(group.filterCommand());
            log(String.format(Locale.ROOT,"Receive range %03X–%03X",group.firstId,group.lastId));
            CanCapture.Snapshot capture=channel.listenCanSnapshot(PassiveNetworkPlan.WINDOW_MILLIS,true);
            JSONObject row=new JSONObject().put("mask",String.format(Locale.ROOT,"%03X",group.mask))
                    .put("filter",String.format(Locale.ROOT,"%03X",group.filter)).put("capture",snapshotJson(capture));
            boolean mismatch=false;
            for(String id:capture.ids.keySet()) {
                if(!group.accepts(Integer.parseInt(id,16)))mismatch=true;
                else otherIdSeen=true;
            }
            row.put("unexpectedIdOutsideFilter",mismatch);groups.put(row);
            if(mismatch) {
                audit.put("verdict","FILTER_BEHAVIOR_MISMATCH");
                if(capture.stopConfirmed && !capture.truncated) {
                    if(dlcAcknowledged)diagnosticOk("ATD0");
                    diagnosticOk("ATCRA");audit.put("defaultReceiveFilterRestored",true);
                }
                finish("NETWORK_AUDIT_STOPPED","Adapter returned an ID outside its acknowledged mask; filter behavior is unverified");return;
            }
            if(!capture.stopConfirmed || capture.truncated)throw new IllegalStateException("Filtered monitor did not finish cleanly; no further operations");
            complete&=!capture.incomplete();
        }
        audit.put("canStatusAfterGroups",diagnosticCommand("ATCS"));
        if(dlcAcknowledged)diagnosticOk("ATD0");
        diagnosticOk("ATCRA");audit.put("defaultReceiveFilterRestored",true);
        audit.put("otherIdsObserved",otherIdSeen).put("allGroupsComplete",complete)
                .put("verdict",!complete?"FILTERED_CAPTURE_INCOMPLETE":otherIdSeen?"OTHER_11BIT_TRAFFIC_OBSERVED":"NO_OTHER_IDS_IN_SAMPLED_WINDOWS")
                .put("scope","Sequential 500ms windows, not a simultaneous full-bus trace. Absent IDs may be dormant, slower, gated, on another bus or require a different format; HVAC remains unverified.")
                .put("canStatusMeaning","Adapter error counters; unsupported ATCS is kept raw. Zero counters alone cannot rule out another transmitter missing ACK.");
        finish("NETWORK_AUDIT_CAPTURED","Receive-only network census completed; not a climate signal or heating capability");
    }
    private JSONObject snapshotJson(CanCapture.Snapshot snap) throws Exception {
        JSONObject can=new JSONObject().put("frameCount",snap.frameCount)
                        .put("framesWithDlc",snap.framesWithDlc).put("framesWithoutDlc",snap.framesWithoutDlc)
                        .put("ignoredLines",snap.ignoredLines)
                        .put("truncated",snap.truncated || channel.monitorTruncated())
                        .put("adapterBufferFull",snap.adapterBufferFull)
                        .put("receivedBytes",snap.receivedBytes)
                        .put("bleNotificationCount",snap.notificationCount)
                        .put("framesAtFirstOverflow",snap.framesAtFirstOverflow<0?JSONObject.NULL:snap.framesAtFirstOverflow)
                        .put("captureElapsedMillis",snap.captureElapsedMillis)
                        .put("startWriteMillis",snap.startWriteMillis).put("lateStartWrite",snap.lateStartWrite).put("windowElapsed",snap.windowElapsed)
                        .put("firstOverflowMillis",snap.firstOverflowMillis<0?JSONObject.NULL:snap.firstOverflowMillis)
                        .put("bytesAtFirstOverflow",snap.bytesAtFirstOverflow<0?JSONObject.NULL:snap.bytesAtFirstOverflow)
                        .put("firstNotificationMillis",snap.firstNotificationMillis<0?JSONObject.NULL:snap.firstNotificationMillis)
                        .put("lastNotificationMillis",snap.lastNotificationMillis<0?JSONObject.NULL:snap.lastNotificationMillis)
                        .put("minNotificationBytes",snap.minNotificationBytes).put("maxNotificationBytes",snap.maxNotificationBytes)
                        .put("maxNotificationGapMillis",snap.maxNotificationGapMillis)
                        .put("partialLinesDiscarded",snap.partialLinesDiscarded).put("invalidFrameLines",snap.invalidFrameLines)
                        .put("overlongLines",snap.overlongLines).put("nonAsciiBytes",snap.nonAsciiBytes)
                        .put("ignoredNullBytes",snap.ignoredNullBytes)
                        .put("stopDrainMillis",snap.stopDrainMillis).put("promptSeen",snap.promptSeen)
                        .put("stopSent",snap.stopSent).put("stopWriteConfirmed",snap.stopWriteConfirmed).put("stopConfirmed",snap.stopConfirmed)
                        .put("sessionBudgetExceeded",snap.sessionBudgetExceeded).put("endReason",snap.endReason)
                        .put("adapterError",snap.adapterError==null?JSONObject.NULL:snap.adapterError)
                        .put("stopError",snap.stopError==null?JSONObject.NULL:snap.stopError)
                        .put("receivedByteRate",snap.lastNotificationMillis>snap.firstNotificationMillis?
                                snap.receivedBytes*1000.0/(snap.lastNotificationMillis-snap.firstNotificationMillis):JSONObject.NULL)
                        .put("throughputMeaning","Host ASCII bytes including status; packet timestamps. Not CAN bitrate, UART speed, or over-the-air BLE throughput")
                        .put("overflowMeasurementResolution","Host BLE notification timestamp and cumulative whole-packet byte count; not internal adapter timing")
                        .put("captureStrategy","500ms-acquisition-then-bounded-prompt-drain")
                        .put("captureQuality",snap.incomplete()?"INCOMPLETE":"UNVERIFIED")
                        .put("interpretation","Undecoded passive frames. Changed IDs are not confirmed HVAC signals.");
                JSONArray ids=new JSONArray();
                for(CanCapture.IdSummary id:snap.ids.values()) {
                    JSONArray samples=new JSONArray();for(String sample:id.samples)samples.put(sample);
                    ids.put(new JSONObject().put("id",id.id).put("count",id.count).put("samples",samples));
                }
                can.put("ids",ids);

        JSONArray packets=new JSONArray();
        for(CanCapture.NotificationTrace packet:snap.notificationTrace)packets.put(new JSONObject().put("bytes",packet.bytes).put("arrivalMicros",packet.arrivalMicros).put("processingMicros",packet.processingMicros));
        JSONArray invalid=new JSONArray();for(String line:snap.invalidLineSamples)invalid.put(line);
        can.put("notificationTrace",packets).put("notificationTraceTruncated",snap.notificationTraceTruncated)
                .put("rawAsciiPrefix",snap.rawAsciiPrefix==null?JSONObject.NULL:snap.rawAsciiPrefix).put("rawTraceTruncated",snap.rawTraceTruncated)
                .put("invalidLineSamples",invalid).put("maxProcessingMicros",snap.maxProcessingMicros).put("maxReceiveLockWaitMicros",snap.maxReceiveLockWaitMicros)
                .put("processingMeaning","ELM receive callback entry, parser time and receiveLock wait; excludes Android stack queue time before callback");
        return can;
    }

    private void log(String s) { ui.post(()->listener.log(s)); }
    private void stateEvent(String name,String value) {
        try{synchronized(report){events.put(new JSONObject().put("at",Instant.now().toString()).put("scope","BLE_STATE")
                .put("event",name).put("value",value).put("elapsedMillis",SystemClock.elapsedRealtime()-startElapsed));}}
        catch(Exception ignored) {}
    }
    private void finish(String result,String detail) {
        if(!terminal.compareAndSet(false,true)) return;
        stateEvent("CLOSE_REQUESTED",result);
        linkConnected=false;
        ui.removeCallbacks(watchdog);
        ui.removeCallbacks(mtuWatchdog);mtuPending.set(false);
        ElmChannel ch=channel; if(ch!=null) ch.close(detail);
        writeGate.close();
        BluetoothGatt g=gatt; gatt=null;
        if(g!=null) { try { g.disconnect(); } catch(Exception ignored) {} try { g.close(); } catch(Exception ignored) {} }
        worker.shutdownNow();
        ui.post(()->{
            try {
                synchronized(report) {
                    if(isHvac()) {
                        String capture=ch==null?"null":ch.hvacCaptureJson();
                        report.put("hvacCapture",capture.equals("null")?JSONObject.NULL:new JSONObject(capture))
                                .put("bleIo",new JSONObject(ioJournal.json()))
                                .put("ownerMarks",new JSONArray(ownerMarks.marksJson()))
                                .put("adapterMayStillBeMonitoring",!report.optBoolean("adapterResetConfirmed"));
                    }
                    report.put("result",result).put("detail",detail).put("finishedAt",Instant.now().toString())
                            .put("sessionMillis",SystemClock.elapsedRealtime()-startElapsed).put("bleCloseRequested",true);
                }
                listener.log(result+": "+detail); listener.done(report);
            } catch(Exception e) { listener.log(e.toString()); }
        });
    }
}
