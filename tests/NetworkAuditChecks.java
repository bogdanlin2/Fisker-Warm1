import com.fiskerwarm.lab.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent acceptance oracle and host-path replay, not a Vgate hardware test. */
public final class NetworkAuditChecks {
    private static int count;
    private static final int[][] EXPECTED={
        {0x400,0x400,1024},{0x600,0x200,512},{0x700,0x100,256},
        {0x780,0x000,128},{0x7C0,0x080,64},{0x7E0,0x0C0,32},
        {0x7F0,0x0F0,16},{0x7F8,0x0E0,8},{0x7FC,0x0EC,4},
        {0x7FE,0x0EA,2},{0x7FF,0x0E8,1}
    };
    private static void require(boolean condition) {if(!condition)throw new AssertionError();}
    private static boolean bitOracle(int id,int mask,int pattern) {
        for(int bit=0;bit<11;bit++)
            if(((mask>>bit)&1)==1 && ((id>>bit)&1)!=((pattern>>bit)&1))return false;
        return true;
    }
    private static byte[] ascii(String text) {return text.getBytes(StandardCharsets.US_ASCII);}
    public static void main(String[] args) throws Exception {
        PassiveNetworkPlan.Group[] groups=PassiveNetworkPlan.groups();
        require(groups.length==EXPECTED.length);
        for(int index=0;index<groups.length;index++) {
            PassiveNetworkPlan.Group group=groups[index];
            require(group.mask==EXPECTED[index][0] && group.filter==EXPECTED[index][1]);
            int accepted=0;
            for(int id=0;id<2048;id++) {
                boolean expected=bitOracle(id,EXPECTED[index][0],EXPECTED[index][1]);
                require(group.accepts(id)==expected);
                if(expected)accepted++;
            }
            require(accepted==EXPECTED[index][2]);count++;
            System.out.println("PASS independent bit oracle and cardinality for group "+index);
        }
        // The emulator has its own mask/pattern state. Feed every accepted ID,
        // not just 0E9, through the real fragmented ELM receive state machine.
        ElmChannel[] channel=new ElmChannel[1];
        int[] adapterMask={0},adapterPattern={0},monitorCount={0};
        List<String> writes=new ArrayList<>();
        channel[0]=new ElmChannel(bytes->{
            String command=new String(bytes,StandardCharsets.US_ASCII).trim();writes.add(command);
            if(command.startsWith("ATCM"))adapterMask[0]=Integer.parseInt(command.substring(4),16);
            else if(command.startsWith("ATCF"))adapterPattern[0]=Integer.parseInt(command.substring(4),16);
            else if(command.equals("ATMA")) {
                monitorCount[0]++;
                for(int id=0;id<2048;id++)if(bitOracle(id,adapterMask[0],adapterPattern[0])) {
                    String frame=String.format(Locale.ROOT,"%03X 1 A5\r",id);
                    channel[0].receive(ascii(frame.substring(0,2)));
                    channel[0].receive(ascii(frame.substring(2)));
                }
                return;
            } else if(command.isEmpty()) {channel[0].receive(ascii("STOPPED\r\r>"));return;}
            else throw new AssertionError("Unexpected command "+command);
            channel[0].receive(ascii("OK\r>"));
        },15000);
        Set<String> allIds=new HashSet<>();
        for(int index=0;index<groups.length;index++) {
            PassiveNetworkPlan.Group group=groups[index];
            require(channel[0].sendAdapterDiagnostic(group.maskCommand(),500).equals("OK"));
            require(channel[0].sendAdapterDiagnostic(group.filterCommand(),500).equals("OK"));
            CanCapture.Snapshot capture=channel[0].listenCanSnapshot(500,true);
            require(!capture.incomplete() && capture.invalidFrameLines==0 && capture.stopConfirmed);
            require(capture.frameCount==EXPECTED[index][2]);
            for(String id:capture.ids.keySet())require(allIds.add(id));
            count++;System.out.println("PASS fragmented positive host-path replay for group "+index);
        }
        require(monitorCount[0]==11 && allIds.size()==2047 && !allIds.contains("0E9"));
        require(writes.size()==44);count++;
        System.out.println("PASS consecutive groups preserve all 2047 synthetic IDs with no hidden reset");
        System.out.println("TOTAL "+count+" CAN regression checks passed; emulated adapter only.");
    }
}
