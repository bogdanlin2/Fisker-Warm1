package com.fiskerwarm.lab.core;

import java.util.Locale;

/** Receive-only, fixed ELM adapter diagnostics. Never authorizes a vehicle payload. */
public final class PassiveNetworkPlan {
    /** Stage 7.3 is a separate, narrower adapter-only capability. No arbitrary IDs. */
    public static boolean hvacAdapterCommand(String command) {
        if(command==null)return false;
        for(String allowed:new String[]{"ATZ","ATE0","ATI","ATRV","ATL0","ATS1","ATH1","ATSP6",
                "ATCAF0","ATCFC0","ATCSM1","ATD1","ATDPN","ATAR","ATCM7FF","ATCF534","ATCF373"})
            if(allowed.equals(command))return true;
        return false;
    }
    public static final int OBSERVED_FLOOD_ID=0x0E9;
    public static final long WINDOW_MILLIS=500;
    public static final class Group {
        public final int mask, filter, firstId, lastId;
        private Group(int mask,int filter) {
            this.mask=mask;this.filter=filter;firstId=filter;lastId=filter | (0x7FF ^ mask);
        }
        public boolean accepts(int id) {return id>=0 && id<=0x7FF && (id & mask)==filter;}
        public String maskCommand() {return String.format(Locale.ROOT,"ATCM%03X",mask);}
        public String filterCommand() {return String.format(Locale.ROOT,"ATCF%03X",filter);}
    }
    private static final Group[] GROUPS=buildGroups();
    private static Group[] buildGroups() {
        Group[] groups=new Group[11];
        for(int bit=10;bit>=0;bit--) {
            int mask=0x7FF & ~((1<<bit)-1);
            // Highest bit that differs from the observed flood ID: disjoint cover.
            groups[10-bit]=new Group(mask,(OBSERVED_FLOOD_ID & mask) ^ (1<<bit));
        }
        return groups;
    }
    public static Group[] groups() {return GROUPS.clone();}
    public static boolean diagnosticAdapterCommand(String command) {
        if(command==null)return false;
        if(command.equals("ATCSM1") || command.equals("ATCS") || command.equals("ATDPN") || command.equals("ATD1") || command.equals("ATD0")
                || command.equals("ATCM000") || command.equals("ATCF000") || command.equals("ATCRA"))return true;
        for(Group group:GROUPS)if(command.equals(group.maskCommand()) || command.equals(group.filterCommand()))return true;
        return false;
    }
    public static boolean safeStartupCommand(String command) {
        return command!=null && (command.equals("ATZ") || command.equals("ATE0") || command.equals("ATI")
                || command.equals("ATRV") || command.equals("ATL0") || command.equals("ATS1")
                || command.equals("ATH1") || command.equals("ATSP6") || command.equals("ATCAF0") || command.equals("ATCFC0"));
    }
    private PassiveNetworkPlan() {}
}
