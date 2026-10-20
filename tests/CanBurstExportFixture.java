import com.fiskerwarm.lab.core.*;
public final class CanBurstExportFixture {
    public static void main(String[] args)throws Exception {
        CanBurstChecks.fixture();CanCapture.Snapshot c=CanBurstChecks.recorded();
        System.out.println("{\"dataOrigin\":\"RECORDED_BYTES_REPLAYED_AS_SYNTHETIC\",\"newVehicleTest\":false,\"controlPresenceObserved\":"+
                CanRxAudit.matchingControlRxObserved(c)+",\"controlRecoveryAllowed\":"+CanRxAudit.controlRecoveryAllowed(c)+
                ",\"capture\":"+HvacJournal.captureJson(c)+"}");
    }
}
