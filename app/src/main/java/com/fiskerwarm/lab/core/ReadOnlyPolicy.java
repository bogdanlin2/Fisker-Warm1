package com.fiskerwarm.lab.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Deliberately VIN-specific. Public repository labels are not a vehicle map. */
public final class ReadOnlyPolicy {
    public static final class Route {
        public final String name, tx, rx, historicalVersion;
        private Route(String name, String tx, String rx, String version) {
            this.name = name; this.tx = tx; this.rx = rx; this.historicalVersion = version;
        }
    }

    private static final Route[] ROUTES = {
        new Route("PKC", "7A2", "7AA", "F160808130021.3"),
        new Route("ECC", "7F0", "7F8", "ECC395 24"),
        new Route("BCM", "7C1", "7C9", "BCM395030"),
        new Route("ICC", "7B1", "7B9", "ICC390C49")
    };
    public static Route[] routes() { return ROUTES.clone(); }
    private static final int[] IDENTITY_DIDS = {0xF195, 0xF188, 0xF191};
    public static int[] identityDids() { return IDENTITY_DIDS.clone(); }
    public static final double MIN_DIAGNOSTIC_VOLTAGE = 12.2;
    public static final long MAX_SESSION_MILLIS = 60_000;
    private static final Set<String> ADAPTER = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP6", "ATCAF1", "ATAL", "ATCFC1", "ATI", "ATRV", "ATCAF0", "ATCFC0")));

    private String tx, rx, flowTx;

    public synchronized void checkAndRecord(String cmd) {
        // Accept only generated, single ASCII commands. Never normalize injected text.
        if (cmd == null || !cmd.equals(cmd.toUpperCase(Locale.ROOT)) ||
                cmd.contains("\r") || cmd.contains("\n") || cmd.contains(" ") || cmd.length() > 16) {
            throw new SecurityException("Invalid command encoding");
        }
        if (ADAPTER.contains(cmd)) {
            if ("ATZ".equals(cmd)) { tx = null; rx = null; flowTx = null; }
            return;
        }
        if("ATMA".equals(cmd)) return; // monitor all: passive adapter receive, no ECU request
        for (Route r : ROUTES) {
            if (cmd.equals("ATSH" + r.tx)) {
                tx = r.tx; rx = null; flowTx = null; return;
            }
            if (cmd.equals("ATCRA" + r.rx)) {
                if (!r.tx.equals(tx)) throw new SecurityException("RX does not match TX");
                rx = r.rx; return;
            }
            if (cmd.equals("ATFCSH" + r.tx)) {
                if (!r.tx.equals(tx) || !r.rx.equals(rx)) throw new SecurityException("Incomplete addressing");
                flowTx = r.tx; return;
            }
        }
        for (int did : IDENTITY_DIDS) {
            if (cmd.equals(String.format(Locale.ROOT, "22%04X", did))) {
                for (Route r : ROUTES) if (r.tx.equals(tx) && r.rx.equals(rx) && r.tx.equals(flowTx)) return;
                throw new SecurityException("UDS addressing not verified");
            }
        }
        // No ECUReset, ClearDTC, SessionControl, SecurityAccess, WriteDID,
        // RoutineControl, TesterPresent, raw CAN transmission, firmware, or drive-module route.
        throw new SecurityException("Command is outside the read-only allowlist: " + cmd);
    }

    public static boolean diagnosticVoltageAcceptable(Double v) {
        return v != null && !v.isNaN() && !v.isInfinite() && v >= MIN_DIAGNOSTIC_VOLTAGE && v <= 16.0;
    }
}
