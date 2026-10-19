package com.fiskerwarm.internet;

/** User-action gates. A background status request never locks the send button. */
public final class RemoteControls {
    private RemoteControls() {}
    public enum Gate { NEED_CONFIG, IMPORTING, CONFIRMING, SUBMITTING, WAITING, READY }

    public static Gate gate(boolean configured, boolean importing, boolean confirming,
                            boolean submitting, Wire.Command pending, boolean terminal, long now) {
        if (importing) return Gate.IMPORTING;
        if (!configured) return Gate.NEED_CONFIG;
        if (confirming) return Gate.CONFIRMING;
        if (submitting) return Gate.SUBMITTING;
        if (pending != null && !terminal && pending.fresh(now)) return Gate.WAITING;
        return Gate.READY;
    }

    public static boolean canRetry(Gate gate) { return gate == Gate.WAITING; }
}
