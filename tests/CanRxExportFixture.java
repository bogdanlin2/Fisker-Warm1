import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
public final class CanRxExportFixture {
    public static void main(String[] args) {
        byte[] tx="ATMA\r".getBytes(StandardCharsets.US_ASCII);
        byte[] rx="0E9 8 2D F0 FF FF 9E 57 FF FF\rSTOPPED\r\r>".getBytes(StandardCharsets.US_ASCII);
        BleIoJournal io=new BleIoJournal();
        for(String k:new String[]{"TX_REQUEST","TX_QUEUE_RESULT","TX_CALLBACK","TX_COMPLETE"})io.add(k,100,1,tx,0,"2AF1","SYNTHETIC_GATT");
        io.add("RX_NOTIFICATION",200,0,rx,-1,"2AF0","SYNTHETIC_ELM_CHANNEL");
        io.add("RX_NOTIFICATION",300,0,"ignored\r>".getBytes(StandardCharsets.US_ASCII),-1,"other#1","OTHER_ENDPOINT");
        CanCapture.Stream stream=new CanCapture.Stream(0,0x0E9);stream.accept(rx,200);
        CanCapture.Snapshot c=stream.finish();c.startWriteConfirmed=true;c.startWriteMillis=4;
        System.out.println("{\"dataOrigin\":\"SYNTHETIC_TEST\",\"vehicleConnected\":false,\"bleIo\":"+io.json()+",\"capture\":"+HvacJournal.captureJson(c)+"}");
    }
}
