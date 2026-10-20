import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
/** Produces explicitly synthetic JSON for an independent Python round-trip check. */
public final class HvacExportFixture {
    public static void main(String[] args) {
        CanCapture.Stream s=new CanCapture.Stream(1_000_000,0x534);
        s.accept("534 8 A5 5A FF 00 ".getBytes(StandardCharsets.US_ASCII),2_000_000);
        s.accept("FE 40 08 11\r534 8 A5 5A FF 00 FE 40 08 12\rSTOPPED\r>".getBytes(StandardCharsets.US_ASCII),5_000_000);
        CanCapture.Snapshot n=s.finish();n.endReason="SYNTHETIC_FIXTURE";n.stopConfirmed=true;n.windowElapsed=true;
        HvacJournal marks=new HvacJournal();marks.mark("HMI_ON",3_000_000,1234);
        System.out.print("{\"dataOrigin\":\"SYNTHETIC_TEST\",\"vehicleClimateStartedByApp\":false,\"capture\":"+HvacJournal.captureJson(n)+",\"ownerMarks\":"+marks.marksJson()+"}");
    }
}
