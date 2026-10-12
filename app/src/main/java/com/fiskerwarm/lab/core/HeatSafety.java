package com.fiskerwarm.lab.core;

import java.util.ArrayList;
import java.util.List;

/** Policy only. There is deliberately no start/stop/wake transport in this build. */
public final class HeatSafety {
    private HeatSafety() {}
    public static final String BUILD_STATUS = "BLOCKED_UNVERIFIED_PROTOCOL";
    public static final class Evidence {
        public final boolean start, stop, wake, authorized, boundedByVehicle, statusFeedback, telemetry;
        public Evidence(boolean start, boolean stop, boolean wake, boolean authorized,
                        boolean boundedByVehicle, boolean statusFeedback, boolean telemetry) {
            this.start=start; this.stop=stop; this.wake=wake; this.authorized=authorized;
            this.boundedByVehicle=boundedByVehicle; this.statusFeedback=statusFeedback; this.telemetry=telemetry;
        }
    }
    public static final Evidence PRODUCTION_EVIDENCE = new Evidence(false,false,false,false,false,false,false);
    public static final class Snapshot {
        public final Boolean inPark;
        public final Double speedKph, voltage12, tractionSoc;
        public final long ageMillis;
        public final boolean authenticatedSource;
        public Snapshot(Boolean inPark, Double speed, Double voltage, Double soc, long age, boolean authenticated) {
            this.inPark=inPark; speedKph=speed; voltage12=voltage; tractionSoc=soc; ageMillis=age;
            authenticatedSource=authenticated;
        }
    }
    public static List<String> blockers(Evidence e, Snapshot s, int minutes, boolean authenticatedRequest) {
        List<String> b=new ArrayList<>();
        if (!e.start) b.add("START_COMMAND_NOT_VERIFIED");
        if (!e.stop) b.add("STOP_COMMAND_NOT_VERIFIED");
        if (!e.wake) b.add("LOCKED_SLEEP_WAKE_NOT_VERIFIED");
        if (!e.authorized) b.add("OWNER_AUTHORIZATION_PROTOCOL_NOT_VERIFIED");
        if (!e.boundedByVehicle) b.add("VEHICLE_SIDE_TIMEOUT_NOT_VERIFIED");
        if (!e.statusFeedback) b.add("CLIMATE_RESULT_FEEDBACK_NOT_VERIFIED");
        if (!e.telemetry) b.add("SAFETY_TELEMETRY_NOT_VERIFIED");
        if (!authenticatedRequest) b.add("REQUEST_NOT_AUTHENTICATED");
        if (minutes < 15 || minutes > 20) b.add("DURATION_OUTSIDE_15_TO_20_MINUTES");
        if (s==null) { b.add("NO_LIVE_TELEMETRY"); return b; }
        if (!s.authenticatedSource) b.add("TELEMETRY_SOURCE_NOT_AUTHENTICATED");
        if (s.ageMillis < 0 || s.ageMillis > 10_000) b.add("TELEMETRY_STALE_OR_TIME_INVALID");
        if (!Boolean.TRUE.equals(s.inPark)) b.add("PARK_NOT_CONFIRMED");
        if (!finite(s.speedKph) || s.speedKph != 0.0) b.add("STATIONARY_NOT_CONFIRMED");
        if (!ReadOnlyPolicy.diagnosticVoltageAcceptable(s.voltage12)) b.add("12V_LOW_OR_UNKNOWN");
        if (!finite(s.tractionSoc) || s.tractionSoc < 20 || s.tractionSoc > 100) b.add("HV_SOC_LOW_OR_UNKNOWN");
        return b;
    }
    private static boolean finite(Double d) { return d!=null && !d.isNaN() && !d.isInfinite(); }
}
