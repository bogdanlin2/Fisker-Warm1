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
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground, finite probes only. GATT-map mode never writes characteristics or descriptors. */
@SuppressLint("MissingPermission")
public final class BleProbe {
    public enum Mode { VOLTAGE_ONLY, IDENTITIES, GATT_MAP, PASSIVE_CAN_OFF, PASSIVE_CAN_ON }
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
    private volatile BluetoothGatt gatt;
    private volatile ElmChannel channel;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private volatile CountDownLatch writeLatch;
    private volatile int writeStatus;
    private final long startElapsed=SystemClock.elapsedRealtime();
    private final Runnable watchdog=()->finish("SESSION_LIMIT", "Hard session deadline reached");

    public BleProbe(Context context,Mode mode,Listener listener) {
        this.context=context.getApplicationContext(); this.mode=mode; this.listener=listener;
    }
    public void start(BluetoothDevice device,String advertisedName) {
        try {
            report.put("format","fisker-warm-lab-1"); report.put("appVersion","0.3.3-lab");
            report.put("mode",mode.toString()); report.put("startedAt",Instant.now().toString());
            report.put("heatCapability",HeatSafety.BUILD_STATUS); report.put("events",events);
            report.put("deviceName",advertisedName); report.put("deviceAddress",device.getAddress());
            report.put("hvSoc",JSONObject.NULL); report.put("gear",JSONObject.NULL);
            report.put("speedKph",JSONObject.NULL); report.put("climateRunning",JSONObject.NULL);
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
    public void cancel() { finish("CANCELLED","User left the foreground or stopped the probe"); }

    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {
            if(terminal.get()) { g.close(); return; }
            if(status!=BluetoothGatt.GATT_SUCCESS) { finish("BLE_ERROR","Connection status "+status); return; }
            if(state==BluetoothProfile.STATE_CONNECTED) {
                log("BLE connected. This does not prove the vehicle is awake.");
                if(!g.discoverServices()) finish("GATT_ERROR","Cannot start service discovery");
            } else if(state==BluetoothProfile.STATE_DISCONNECTED) finish("DISCONNECTED","Peripheral disconnected");
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) {
            if(terminal.get()) return;
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
                channel=new ElmChannel(BleProbe.this::write,Math.max(1,ReadOnlyPolicy.MAX_SESSION_MILLIS-(SystemClock.elapsedRealtime()-startElapsed)));
                if(!g.setCharacteristicNotification(notify,true)) { finish("NOTIFY_ERROR","Local subscription failed"); return; }
                BluetoothGattDescriptor d=notify.getDescriptor(CCC);
                if(d==null) { finish("NOTIFY_ERROR","No CCC descriptor"); return; }
                byte[] value=(notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0?
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                if(Build.VERSION.SDK_INT>=33) {
                    if(g.writeDescriptor(d,value)!=0) finish("NOTIFY_ERROR","Descriptor write did not start");
                } else {
                    d.setValue(value); if(!g.writeDescriptor(d)) finish("NOTIFY_ERROR","Descriptor write did not start");
                }
            } catch(Exception e) { finish("GATT_ERROR",e.toString()); }
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) {
            if(terminal.get()) return;
            if(status!=BluetoothGatt.GATT_SUCCESS) { finish("NOTIFY_ERROR","Subscription status "+status); return; }
            if(started.compareAndSet(false,true)) worker.execute(BleProbe.this::read);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) {
            CountDownLatch latch=writeLatch; writeStatus=status; if(latch!=null) latch.countDown();
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {
            ElmChannel ch=channel;
            if(ch!=null && notifyChar!=null && notifyChar.getUuid().equals(c.getUuid()) && !terminal.get()) ch.receive(value.clone());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
            if(Build.VERSION.SDK_INT<33) { byte[] v=c.getValue(); if(v!=null) onCharacteristicChanged(g,c,v); }
        }
    };

    private void write(byte[] bytes) throws Exception {
        if(terminal.get() || gatt==null || writeChar==null) throw new IllegalStateException("No active UART");
        int type=(writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        CountDownLatch latch=new CountDownLatch(1); writeLatch=latch; writeStatus=-1;
        boolean queued;
        if(Build.VERSION.SDK_INT>=33) queued=gatt.writeCharacteristic(writeChar,bytes,type)==0;
        else { writeChar.setWriteType(type); writeChar.setValue(bytes); queued=gatt.writeCharacteristic(writeChar); }
        if(!queued) throw new IllegalStateException("BLE write did not start");
        // A GATT response is required for confirmed writes. Write-without-response
        // is confirmed only by the ELM prompt inside ElmChannel, not by an invented ACK.
        if(type==BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            if(!latch.await(3000,TimeUnit.MILLISECONDS) || writeStatus!=BluetoothGatt.GATT_SUCCESS)
                throw new IllegalStateException("BLE write failed or timed out");
        }
        writeLatch=null;
    }
    private String command(String cmd,long timeout) throws Exception {
        if(terminal.get()) throw new IllegalStateException("Stopped");
        log("TX "+cmd);
        String r=channel.send(cmd,timeout);
        synchronized(report) { events.put(new JSONObject().put("at",Instant.now().toString()).put("command",cmd).put("reply",r)); }
        log("RX "+r.replace('\r',' ').replace('\n',' ')); return r;
    }
    private void expectOk(String cmd) throws Exception {
        String r=command(cmd,2500); if(!"OK".equals(r.trim())) throw new IllegalStateException(cmd+" was not acknowledged: "+r);
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
            if(mode==Mode.PASSIVE_CAN_OFF || mode==Mode.PASSIVE_CAN_ON) {
                for(String cmd:new String[]{"ATL0","ATS1","ATH1","ATSP6","ATCAF0","ATCFC0"}) expectOk(cmd);
                String stage=mode==Mode.PASSIVE_CAN_OFF?"OFF":"ON";
                report.put("observationPhase",stage);
                report.put("phaseMeaning","User-reported HVAC state, not verified by car");
                report.put("captureSeconds",0.5);
                log("Listening to CAN for 0.5 seconds; no UDS requests or CAN frames transmitted.");
                CanCapture.Snapshot snap=channel.listenCanSnapshot(500);
                JSONObject can=new JSONObject().put("frameCount",snap.frameCount)
                        .put("ignoredLines",snap.ignoredLines)
                        .put("truncated",snap.truncated || channel.monitorTruncated())
                        .put("adapterBufferFull",snap.adapterBufferFull)
                        .put("captureStrategy","short-500ms-stop-on-overflow")
                        .put("captureQuality",(snap.adapterBufferFull || snap.truncated || channel.monitorTruncated())?"INCOMPLETE":"UNVERIFIED")
                        .put("interpretation","Undecoded passive frames. Changed IDs are not confirmed HVAC signals.");
                JSONArray ids=new JSONArray();
                for(CanCapture.IdSummary id:snap.ids.values()) {
                    JSONArray samples=new JSONArray();for(String sample:id.samples)samples.put(sample);
                    ids.put(new JSONObject().put("id",id.id).put("count",id.count).put("samples",samples));
                }
                can.put("ids",ids);report.put("passiveCan",can);
                log("Passive frames: "+snap.frameCount+" · CAN IDs: "+snap.ids.size());
                if(snap.adapterBufferFull || snap.truncated || channel.monitorTruncated())
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
    private void log(String s) { ui.post(()->listener.log(s)); }
    private void finish(String result,String detail) {
        if(!terminal.compareAndSet(false,true)) return;
        ui.removeCallbacks(watchdog);
        ElmChannel ch=channel; if(ch!=null) ch.close(detail);
        CountDownLatch latch=writeLatch; if(latch!=null) latch.countDown();
        BluetoothGatt g=gatt; gatt=null;
        if(g!=null) { try { g.disconnect(); } catch(Exception ignored) {} try { g.close(); } catch(Exception ignored) {} }
        worker.shutdownNow();
        ui.post(()->{
            try {
                synchronized(report) {
                    report.put("result",result).put("detail",detail).put("finishedAt",Instant.now().toString())
                            .put("sessionMillis",SystemClock.elapsedRealtime()-startElapsed).put("bleCloseRequested",true);
                }
                listener.log(result+": "+detail); listener.done(report);
            } catch(Exception e) { listener.log(e.toString()); }
        });
    }
}
