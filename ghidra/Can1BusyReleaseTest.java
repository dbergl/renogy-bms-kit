// CAN1 busy-release acceptance. Args: patched global S19, stock global S19.
// Stock keeps a 4-word (or multiple-of-4) CAN1 request busy until the next main
// loop pass after its final frame; a request arriving in between is dropped.
// The fix branches on the decremented count: zero enters stock's count==0
// completion (busy clears with the final frame, as stock does for 1-3 words);
// nonzero returns unconditionally without reading the PIT-owned pacing flag.
// Executes the real CAN1 receiver, CAN1 transmitter, MSCAN driver and PIT
// pacing helper. MMIO is a RAM fixture, not bus timing.
//@category Test
import java.util.*;

public class Can1BusyReleaseTest extends Can1MaskTest {
    final byte[] poison=poison();
    static byte[] poison() {byte[] p=new byte[13];Arrays.fill(p,(byte)0xCC);return p;}
    boolean fixed;
    byte[] tailOverride; // negative controls only
    int masked;
    int[] counts={4,5,7,8,12,40};
    void boot(boolean withFix) throws Exception {
        init();emu.writeRegister("PPAGE",0xFC);word(0x2622,0x180);byteAt(0x2252,0x0C);
        if(!withFix) restoreStock(emu,0x7FAD5F,0x7FAD6D);
        if(tailOverride!=null) emu.writeMemory(g(0x7FAD5F),tailOverride);
        fixed=withFix;
    }
    int[] state() throws Exception {
        return new int[]{byteAt(0x26DF),byteAt(0x26E1),word(0x26E2),word(0x26E4),word(0x26DB)};
    }
    // Paced transmit call (pacing flag set, TX buffer free). Returns the frame or null.
    byte[] pacedSend() throws Exception {
        byteAt(0x26E1,1);emu.writeMemory(g(0x186),new byte[]{7});emu.writeMemory(g(0x1B0),poison);
        byte[] frame=send();
        return Arrays.equals(frame,poison)?null:frame;
    }
    void probeRejected(String why) throws Exception {
        int[] before=state();request(0x13A0,2,0x81);
        check(Arrays.equals(state(),before),why);
    }
    void probeAccepted(String why) throws Exception {
        request(0x13A0,2,0x81);
        check(byteAt(0x26DF)==1 && word(0x26E2)==24 && word(0x26E4)==2,why);
    }
    // One real request through RX, every frame through TX, then a probe request
    // at the instant the final frame has been handed to the driver.
    int sequence(int start,int count,int seed) throws Exception {
        byte[] data=table(seed);emu.writeMemory(ram(0x23AE),data);
        byteAt(0x26DF,0);byteAt(0x26E1,0);request(0x1388+start,count,0x80);
        check(word(0x26E2)==start && word(0x26E4)==count && byteAt(0x26DF)==1,"real request accepted");
        int sent=0,frames=0;
        for(int step=0;;step++) {
            check(step<12,"multi-frame request completes");
            if(count==0) break;
            byte[] frame=pacedSend();
            int words=Math.min(4,count-sent);
            check(frame!=null,"paced frame sent");
            assertFrame(frame,expected(data,start+sent,words,false),words,0x80);
            sent+=words;frames++;
            if(sent<count) {
                check(byteAt(0x26DF)==1 && byteAt(0x26E1)==0,"busy held between frames");
                probeRejected("new request ignored while frames remain");
            } else break;
        }
        check(word(0x26E4)==0 && word(0x26E2)==start+count,"all words sent exactly once");
        boolean stockHold=!fixed && count>0 && count%4==0;
        if(count==0) {
            // Zero-length requests complete on the next call in both builds.
            check(pacedSend()==null && byteAt(0x26DF)==0,"zero-length request completes silently");
        } else if(stockHold) {
            check(byteAt(0x26DF)==1,"stock holds busy after final 4-word frame");
            probeRejected("stock drops a request arriving before the next main-loop pass");
            byteAt(0x26E1,0);emu.writeMemory(g(0x1B0),poison);
            check(Arrays.equals(send(),poison) && byteAt(0x26DF)==0,"stock releases on the next pass without a frame");
        } else {
            check(byteAt(0x26DF)==0 && byteAt(0x26E1)==0,"busy and pacing cleared with the final frame");
        }
        probeAccepted("request immediately after the final frame is accepted");
        return frames;
    }
    void sequences() throws Exception {
        int cases=0,frames=0,stockDrops=0;
        for(boolean withFix:new boolean[]{true,false}) {
            boot(withFix);
            for(int start=0;start<40;start++) for(int count=0;count<=40-start;count++) {
                if(!withFix && count>0 && count%4==0) stockDrops++;
                frames+=sequence(start,count,cases++);
            }
            emu.dispose();
        }
        println("PASS: "+cases+" real RX/TX sequences ("+frames+" frames): fixed build releases busy with every final frame; stock reproduces "+stockDrops+" dropped-request windows");
    }
    // Released busy must not bypass pacing: the next reply waits for the pacing flag.
    void pacing() throws Exception {
        boot(true);byte[] data=table(7);emu.writeMemory(ram(0x23AE),data);
        for(int count:new int[]{4,8,40}) {
            byteAt(0x26DF,0);request(0x1388,count,0x80);
            int sent=0;
            while(sent<count) {assertFrame(pacedSend(),expected(data,sent,4,false),4,0x80);sent+=4;
                if(sent<count){byteAt(0x26E1,0);emu.writeMemory(g(0x1B0),poison);
                    int[] held=state();check(Arrays.equals(send(),poison) && Arrays.equals(state(),held),"unpaced call sends nothing mid-request");}}
            check(byteAt(0x26DF)==0 && byteAt(0x26E1)==0,"released and unpaced");
            request(0x1388+4,4,0x80);
            emu.writeMemory(g(0x1B0),poison);int[] waiting=state();
            check(Arrays.equals(send(),poison) && Arrays.equals(state(),waiting),"next reply waits for the pacing tick");
            assertFrame(pacedSend(),expected(data,4,4,false),4,0x80);
            check(byteAt(0x26DF)==0,"next 4-word reply also releases");
        }
        // A blocked final frame (driver buffer unavailable) keeps busy and the count.
        for(int blocked=0;blocked<2;blocked++) {
            byteAt(0x26DF,0);request(0x1388,8,0x80);assertFrame(pacedSend(),expected(data,0,4,false),4,0x80);
            byteAt(0x26E1,1);emu.writeMemory(g(0x186),new byte[]{0});emu.writeMemory(g(0x1B0),poison);
            if(blocked==0) {check(Arrays.equals(send(),poison),"blocked final frame writes nothing");}
            else {
                emu.writeMemory(g(0x186),new byte[]{7});counters();
                farEntry(0xAC93);runTo(0xACD3,1000);emu.writeMemory(g(0x186),new byte[]{0});
                Trace trace=new Trace(emu,0x2200);trace.heaterPolicy(txAllowed);
                try {runTo(0x4010,10000);} finally {trace.dispose();}
                check(Arrays.equals(emu.readMemory(g(0x1B0),13),poison),"driver-check failure writes nothing");
            }
            check(byteAt(0x26DF)==1 && word(0x26E2)==4 && word(0x26E4)==4,"blocked final frame retains busy/index/count");
            probeRejected("request ignored while final frame is pending");
            assertFrame(pacedSend(),expected(data,4,4,false),4,0x80);
            check(byteAt(0x26DF)==0,"retried final frame releases busy");
        }
        emu.dispose();
        println("PASS: busy release keeps stock pacing, multi-frame holds and blocked-send retries");
    }
    // Real PIT pacing helper (FUN_7fa6fb) as an interrupt: I set, 16-byte frame, registers restored.
    void pacingInterrupt() throws Exception {
        String[] names={"PC","SP","D","IX","IY","CCR","CCRH","PPAGE","RPAGE","EPAGE"};
        long[] saved=new long[names.length];
        for(int i=0;i<names.length;i++) saved[i]=emu.readRegister(names[i]).longValue();
        emu.writeRegister("SP",reg("SP")-16);emu.writeRegister("CCR",reg("CCR")|16);
        byteAt(0x23A0,4); // fifth divider call: rearms the pacing flag
        farEntry(0xA6FB);runTo(0x4010,10000);
        check(byteAt(0x26E1)==1,"real pacing helper rearms 26E1");
        check(reg("SP")==saved[1]-16,"pacing helper restores stack");
        for(int i=0;i<names.length;i++) emu.writeRegister(names[i],saved[i]);
    }
    // One transmitter call from a paced, busy request with `count` words left.
    void startCall(byte[] data,int count) throws Exception {
        txFixture(data,0,count,0x0C,0x80);farEntry(0xAC93);
    }
    ArrayList<Integer> path(byte[] data,int count) throws Exception {
        boot(fixed);startCall(data,count);ArrayList<Integer> path=new ArrayList<>();
        while((pc()&65535)!=0x4010){check(path.size()<20000,"call returns");path.add((int)(pc()&65535));step();}
        emu.dispose();return path;
    }
    // Inject a pacing interrupt or a CAN1 request at every instruction boundary of
    // one complete transmitter call (including the MSCAN driver) and check that
    // the partial-copy path never sees more than 3 words, one exact frame is sent,
    // the far return frame and stack survive, and busy/count end consistently.
    int boundaries(boolean withFix) throws Exception {
        byte[] data=table(5);int cases=0;fixed=withFix;
        for(int count:counts) {
            ArrayList<Integer> path=path(data,count);
            for(int kind=0;kind<2;kind++) for(int boundary=0;boundary<path.size();boundary++) {
                boot(withFix);startCall(data,count);
                for(int i=0;i<boundary;i++) step();
                if((reg("CCR")&16)!=0){masked++;emu.dispose();continue;} // interrupts masked here
                boolean probeAccepted=false;
                if(kind==0) pacingInterrupt();
                else {int before=byteAt(0x26DF);request(0x13A0,2,0x81);probeAccepted=before==0;
                      check(before==1 || byteAt(0x26DF)==1,"probe accepted only when idle");}
                int n=0;
                while((pc()&65535)!=0x4010) {
                    check(n++<20000,"call returns after interrupt");
                    if((pc()&65535)==0xAD8D) check(word(0x26E4)<=3,"partial copy entered with "+word(0x26E4)+" words");
                    step();
                }
                check(reg("SP")==0x2200 && reg("PPAGE")==0xFC,"stack/page after interrupt");
                check(Arrays.equals(emu.readMemory(ram(0x21FD),3),new byte[]{(byte)0xFC,0x40,0x10}),"far return frame intact");
                int words=Math.min(4,count);
                assertFrame(emu.readMemory(g(0x1B0),13),expected(data,0,words,false),words,0x80);
                if(probeAccepted) check(word(0x26E2)==24 && word(0x26E4)==2 && byteAt(0x26DF)==1,"probe after release owns the request");
                else {
                    check(word(0x26E2)==words && word(0x26E4)==count-words,"one frame of progress");
                    check(byteAt(0x26DF)==(count-words==0?0:1),"busy cleared exactly when nothing remains");
                }
                emu.dispose();cases++;
            }
        }
        return cases;
    }
    void interrupts() throws Exception {
        masked=0;int cases=boundaries(true);
        println("PASS: "+cases+" pacing-interrupt/CAN1-request injections at every unmasked instruction boundary of the transmitter ("+masked+" masked boundaries skipped); no oversized copy, return frame intact");
    }
    void mutation() throws Exception {
        boot(true);restoreStock(emu,0x7FAD5F,0x7FAD6D);fixed=true; // stock bytes, fixed expectations
        rejects("CAN1 busy held after final 4-word frame",()->sequence(0,4,1));
        emu.dispose();
        // The first candidate: stock tail with JMP AE16 -> JMP AD6D. Re-reads the
        // pacing flag, so a pacing interrupt sends a >4-word remainder into the
        // partial-copy path and overruns the stack buffer.
        byte[] racy=HexFormat.of().parseHex("FE26E41A1C7E26E47926E106AD6D");
        rejects("CAN1 busy release re-reads interrupt-owned pacing flag",()->{
            tailOverride=racy;
            try {boundaries(true);} finally {tailOverride=null;}
        });
        emu.dispose();
    }
    @Override public void run() throws Exception {
        loadImage();
        disassemble(g(0x7FAD5F));disassemble(g(0x7FAD6D));disassemble(g(0x7FADB2));
        sequences();pacing();interrupts();mutation();
        println("CAN1 BUSY RELEASE ACCEPTANCE PASSED");
    }
}
