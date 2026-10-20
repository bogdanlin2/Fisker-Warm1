package com.fiskerwarm.lab.core;

import java.util.ArrayList;
import java.util.List;

/** Raw callback-boundary evidence, independent of ELM waiting/monitoring and CAN parsing. */
public final class BleIoJournal {
    private static final int MAX_EVENTS=8192,MAX_BYTES=600_000;
    private final List<String> rows=new ArrayList<>();
    private int bytes,dropped;
    public synchronized boolean add(String kind,long nanos,long txId,byte[] value,int status,String endpoint,String route) {
        if(value==null)value=new byte[0];
        if(rows.size()>=MAX_EVENTS || bytes+value.length>MAX_BYTES){dropped++;return false;}
        bytes+=value.length;
        rows.add("{\"kind\":"+HvacJournal.quote(kind)+",\"receivedNanos\":"+nanos+",\"txId\":"+txId+
                ",\"hex\":"+HvacJournal.quote(ElmParser.hexText(value))+",\"status\":"+status+
                ",\"endpoint\":"+HvacJournal.quote(endpoint)+",\"route\":"+HvacJournal.quote(route)+"}");
        return true;
    }
    public synchronized String json(){return "{\"clock\":\"System.nanoTime\",\"scope\":\"HOST_GATT_CALLBACK_NOT_CAN_WIRE\",\"rawBytesRetained\":"+bytes+
            ",\"droppedEvents\":"+dropped+",\"complete\":"+(dropped==0)+",\"events\":["+String.join(",",rows)+"]}";}
}
