package com.fiskerwarm.lab.core;

import java.util.Locale;

/** Published UART UUID triples only. Properties alone cannot authorize a write. */
public final class UartProfiles {
    private UartProfiles() {}
    public static final class Profile {
        public final String service, write, notify;
        private Profile(String s,String w,String n) { service=s;write=w;notify=n; }
    }
    private static String shortId(String id) { return "0000"+id+"-0000-1000-8000-00805f9b34fb"; }
    private static final Profile[] KNOWN={
        new Profile(shortId("18f0"),shortId("2af1"),shortId("2af0")),
        new Profile(shortId("fff0"),shortId("fff2"),shortId("fff1")),
        new Profile(shortId("ffe0"),shortId("ffe1"),shortId("ffe1")),
        new Profile("6e400001-b5a3-f393-e0a9-e50e24dcca9e","6e400002-b5a3-f393-e0a9-e50e24dcca9e","6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    };
    public static Profile[] known() { return KNOWN.clone(); }
    public static boolean sameEndpoint(String serviceA,int serviceInstanceA,String charA,int charInstanceA,
                                       String serviceB,int serviceInstanceB,String charB,int charInstanceB) {
        return serviceA!=null && serviceA.equals(serviceB) && charA!=null && charA.equals(charB)
                && serviceInstanceA==serviceInstanceB && charInstanceA==charInstanceB;
    }
    public static boolean matches(String service,String write,String notify) {
        if(service==null || write==null || notify==null)return false;
        for(Profile p:KNOWN) if(p.service.equals(service.toLowerCase(Locale.ROOT)) &&
                p.write.equals(write.toLowerCase(Locale.ROOT)) && p.notify.equals(notify.toLowerCase(Locale.ROOT)))return true;
        return false;
    }
}
