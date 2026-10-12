package com.fiskerwarm.lab.core;

/**
 * Foreground probe ownership. Prevents a previous cancelled BLE callback from
 * releasing or overwriting a newer session; a session is only released on done.
 * This does not send Bluetooth, AT, CAN, or climate commands.
 */
public final class ProbeGate {
    private long nextId = 1L;
    private long activeId = 0L;

    public synchronized long begin() {
        if (activeId != 0) throw new IllegalStateException("Previous probe has not completed");
        if (nextId == Long.MAX_VALUE) throw new IllegalStateException("Probe ID exhausted");
        activeId = nextId++;
        return activeId;
    }

    public synchronized boolean complete(long id) {
        if (id == 0 || activeId != id) return false;
        activeId = 0;
        return true;
    }

    public synchronized boolean busy() { return activeId != 0; }
}
