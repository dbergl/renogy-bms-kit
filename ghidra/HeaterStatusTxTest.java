// CAN1 heater status broadcast acceptance. Args: patched global S19, stock global S19.
// Executes the real frame builder, the real MSCAN transmit driver, the real CAN1
// receiver and the real stock CAN1 transmitter. MMIO is a RAM fixture, not a model
// of arbitration or bus timing. See docs/can-protocol.md.
//@category Test
import java.util.*;

public class HeaterStatusTxTest extends Can1BusyReleaseTest {
    static final int ACK=0x5738, PERIODIC=0x573F, FAST=0x5757, SEND=0x5770, END=0x57E0;
    static final int PERIODIC_TAIL=0x5743, FAST_TAIL=0x575B, RETURN=0x4010;
    final int[] statusAllowed={0x186,0x18A,0x1B0,0x1B1,0x1B2,0x1B3,0x1B4,0x1B5,0x1B6,0x1B7,
        0x1B8,0x1B9,0x1BA,0x1BB,0x1BC,0x2253};
    static byte[] mark() {byte[] p=new byte[13];Arrays.fill(p,(byte)0xCC);return p;}
    final byte[] MARK=mark();
    int frames;

    void ready() throws Exception {
        init(); emu.writeRegister("PPAGE",0xFC); word(0x2622,0x180);
        byteAt(0x2253,0); byteAt(0x2416,0); byteAt(0x2252,0); byteAt(0x2256,0);
    }
    void bus(int freeBuffers) throws Exception {
        emu.writeMemory(g(0x186),new byte[]{(byte)freeBuffers});
        emu.writeMemory(g(0x1B0),MARK);
    }
    byte[] frame() throws Exception { return emu.readMemory(g(0x1B0),13); }
    boolean sent(byte[] f) { return !Arrays.equals(f,MARK); }

    // JSR/RTS entry: the hooks live in the unpaged window and are reached by a
    // 3-byte JSR from page FE, so the return frame is two bytes, not three.
    void nearCall(int entry) throws Exception {
        int sp=reg("SP"), page=reg("PPAGE");
        emu.writeRegister("SP",sp-2);
        emu.writeMemory(ram(sp-2),new byte[]{0x40,0x10});
        emu.writeRegister("PC",0x7F0000L|entry);
        runTo(RETURN,30000);
        check(reg("SP")==sp,"status hook stack balance");
        check(reg("PPAGE")==page,"status hook restores the interrupted page");
    }
    boolean beat() throws Exception {                 // one 100 ms task call
        bus(1); traced(()->nearCall(PERIODIC_TAIL),statusAllowed);
        boolean did=sent(frame()); if(did) frames++; return did;
    }
    boolean pass() throws Exception {                 // one main-loop pass
        bus(1); traced(()->nearCall(FAST_TAIL),statusAllowed);
        boolean did=sent(frame()); if(did) frames++; return did;
    }
    // The payload the firmware should have built, derived independently from RAM.
    byte[] expectedStatus() throws Exception {
        boolean armed=word(0x2250)==0xAA55;
        int lease=armed?word(0x2254):0, temp=word(0x233C);
        int flags=(armed?1:0)|(byteAt(0x2252)==0xA5?2:0)
                 |(byteAt(0x2256)!=0?4:0)|(word(0x2BEC)==3?8:0);
        return new byte[]{0x70,(byte)byteAt(0x2350),(byte)flags,(byte)byteAt(0x2416),
            (byte)lease,(byte)(lease>>8),(byte)temp,(byte)(temp>>8)};
    }
    void assertStatus(byte[] actual,String why) throws Exception {
        check(Arrays.equals(Arrays.copyOf(actual,4),encoded(0x18FF7070,true,false)),
            "status ID 18FF7070: "+why);
        check(Arrays.equals(Arrays.copyOfRange(actual,4,12),expectedStatus()),
            "status payload: "+why+" got="+HexFormat.of().formatHex(Arrays.copyOfRange(actual,4,12))
            +" want="+HexFormat.of().formatHex(expectedStatus()));
        check((actual[12]&255)==8,"status DLC: "+why);
    }
    int pending() throws Exception { return byteAt(0x2253)&0x40; }
    // Interior addresses below are hard-coded; confirm each still holds the
    // instruction it is meant to, so a change to status_tx() fails here loudly
    // instead of leaving a negative control rejecting for an unrelated reason.
    void expectCode(long global,String hex,String what) throws Exception {
        byte[] want=HexFormat.of().parseHex(hex);
        for(int i=0;i<want.length;i++)
            check(read(global+i)==(want[i]&255),"image layout: "+what+" at "+Long.toHexString(global+i));
    }
    int divider() throws Exception { return byteAt(0x2253)&0x3F; }

    // 1. Unconditional cadence: exactly one frame per 20 serviced ticks, in every
    //    heater and system state. The gating this replaced is gone for good.
    void cadence() throws Exception {
        for(int relay:new int[]{0,1,4,5}) for(int state:new int[]{0,3,4}) {
            ready(); byteAt(0x2350,relay); word(0x2BEC,state);
            int count=0; List<Integer> at=new ArrayList<>();
            for(int i=1;i<=200;i++) if(beat()) {count++; at.add(i);}
            check(count==10,"10 frames in 200 ticks, relay="+relay+" state="+state+" got="+count);
            for(int i=0;i<at.size();i++) check(at.get(i)==20*(i+1),"frame on every 20th tick");
            emu.dispose();
        }
        // A nonzero divider makes the first frame early, never late. The single
        // seed 0x3F carries out of bit 5 into the request bit, costing one tick
        // and one extra frame; startupZeroFill() proves it cannot occur.
        for(int seed=0;seed<64;seed++) {
            ready(); byteAt(0x2253,seed); int first=0;
            for(int i=1;i<=40 && first==0;i++) if(beat()) first=i;
            // seed<20: the rest of the period; 20..0x3E: the very next tick;
            // 0x3F: one tick later, because the carry lands in bit 6.
            check(first==(seed<20?20-seed:seed==0x3F?21:1),
                "divider seed="+seed+" first="+first);
            check(divider()==0,"divider reset after sending");
            check((pending()!=0)==(seed==0x3F),"only seed 0x3F carries into the request bit");
            emu.dispose();
        }
        println("PASS: unconditional 2 s cadence in 12 relay/state combinations and 64 divider seeds");
    }

    // 1b. The divider byte is zero at every reset, so the one carrying seed cannot
    //     occur on hardware. Read the startup zero-fill table out of the image
    //     itself rather than assuming a C runtime clears bss.
    void startupZeroFill() throws Exception {
        ready();
        int blocks=(read(0x7F400F)<<8)|read(0x7F4010);
        int table=(read(0x7F4011)<<8)|read(0x7F4012);
        Set<Integer> zeroed=new TreeSet<>();
        for(int i=0;i<blocks;i++) {
            int b=0x7F0000|(table+i*5);
            long dest=((long)read(b)<<16)|(read(b+1)<<8)|read(b+2);
            int count=(read(b+3)<<8)|read(b+4);
            println("startup zero-fill block "+i+": "+Long.toHexString(dest)+" count="+count);
            for(int local:new int[]{0x2250,0x2251,0x2252,0x2253,0x2254,0x2255})
                if(dest<=RAM+local && RAM+local<dest+count) zeroed.add(local);
        }
        // 0x2253 is the divider/request byte; 0x2250-51 (session magic), 0x2252
        // (mask magic) and 0x2254-55 (lease) being zero means no manual session
        // and no mask are armed at reset, which the status frame reports as such.
        check(blocks>0 && zeroed.size()==6,"startup zero-fill covers 0x2250-0x2255, got "+zeroed);
        emu.dispose();
        println("PASS: 0x2250-0x2255 (session, mask, divider/request, lease) are zeroed by startup_copydown at every reset");
    }

    // 2/3. Every accepted command arms bit 6, and the next main-loop pass sends a
    //      frame that already reflects the applied command.
    void acknowledges() throws Exception {
        for(int lease:new int[]{0,1,300,65535}) {
            ready(); check(pending()==0,"nothing pending before the command");
            can1Command(0xAA55,8,false,lease);
            check(pending()!=0,"heater command arms the status request lease="+lease);
            check(word(0x2254)==lease,"heater command applied before the ack is armed");
            check(pass(),"acknowledgement frame on the next main-loop pass");
            assertStatus(frame(),"heater ack lease="+lease);
            check(pending()==0,"request consumed");
            check(!pass(),"exactly one acknowledgement frame");
            emu.dispose();
        }
        for(int ticks:new int[]{0,100,65535}) {
            ready(); can1Frame(0x18EF7080,true,8,false,leaseData(ticks));
            check(pending()!=0,"mask command arms the status request ticks="+ticks);
            check(byteAt(0x2252)==(ticks==0?0:0xA5),"mask command applied");
            check(pass(),"mask acknowledgement frame");
            assertStatus(frame(),"mask ack ticks="+ticks);
            check(pending()==0 && !pass(),"exactly one mask acknowledgement");
            emu.dispose();
        }
        println("PASS: heater and mask commands, on and off, each acknowledged exactly once");
    }

    // 4. A frame the dispatcher rejects must arm nothing and send nothing. This is
    //    the property that makes the ack an acceptance test rather than a bus ACK.
    void rejectsSilently() throws Exception {
        int cases=0;
        for(int dlc=0;dlc<16;dlc++) for(boolean rtr:new boolean[]{false,true}) {
            ready(); can1Frame(0x18EF7080,true,dlc,rtr,heaterData(300));
            boolean valid=dlc==8 && !rtr;
            check((pending()!=0)==valid,"DLC/RTR gating dlc="+dlc+" rtr="+rtr);
            if(!valid) check(!pass(),"rejected frame sends nothing");
            emu.dispose(); cases++;
        }
        for(int state=0;state<6;state++) {
            ready(); word(0x2BEC,state); can1Command(0xAA55,8,false,300);
            check((pending()!=0)==(state==3||state==4),"state gating state="+state);
            if(state!=3 && state!=4) check(!pass(),"command outside state 3/4 sends nothing");
            emu.dispose(); cases++;
        }
        for(int magic:new int[]{0,0xAA54,0xAA56,0x4D52,0x4D54,0xFFFF}) {
            ready(); can1Frame(0x18EF7080,true,8,false,payload(magic,300));
            check(pending()==0,"wrong magic arms nothing: "+Integer.toHexString(magic));
            check(!pass(),"wrong magic sends nothing"); emu.dispose(); cases++;
        }
        // The mask payload validator lives inside mask_toggle, past the point
        // where the dispatcher arms the request, so a 4D53 frame with a nonzero
        // reserved byte IS acknowledged. It applies nothing, and the frame says
        // so: the acknowledgement carries state, not a verdict.
        for(int index=2;index<6;index++) {
            ready(); byte[] data=leaseData(100); data[index]=1;
            int was=byteAt(0x2252), lease=word(0x240E);
            can1Frame(0x18EF7080,true,8,false,data);
            check(pending()!=0,"dispatcher-level acceptance acknowledges, reserved byte "+index);
            check(byteAt(0x2252)==was && word(0x240E)==lease,
                "reserved byte "+index+" applies nothing");
            check(pass(),"acknowledgement frame for reserved byte "+index);
            byte[] f=frame();
            check((f[6]&2)==0,"acknowledgement reports the mask still disabled");
            assertStatus(f,"unapplied mask command, reserved byte "+index);
            emu.dispose(); cases++;
        }
        for(int id:new int[]{0x18EF7180,0x18EE7080,0x19EF7080,0x999}) {
            ready(); can1Frame(id,true,8,false,heaterData(300));
            check(pending()==0,"foreign ID arms nothing: "+Integer.toHexString(id));
            check(!pass(),"foreign ID sends nothing"); emu.dispose(); cases++;
        }
        println("PASS: "+cases+" frames: dispatcher-rejected ones are silent, payload-rejected ones acknowledge unchanged state");
    }

    // 5. The acknowledgement and the divider share one byte and must not disturb
    //    each other: BCLR #3F keeps bit 6, BSET #40 keeps the count.
    void sharedByte() throws Exception {
        ready();
        for(int i=1;i<=19;i++) check(!beat(),"no heartbeat before the 20th tick");
        check(divider()==19,"divider advanced to 19");
        can1Command(0xAA55,8,false,300);
        check(divider()==19 && pending()!=0,"arming leaves the divider alone");
        check(pass(),"acknowledgement while the divider is loaded");
        check(divider()==19 && pending()==0,"acknowledgement leaves the divider alone");
        check(beat(),"heartbeat still lands on its own 20th tick");
        check(divider()==0 && pending()==0,"heartbeat clears only the divider");
        emu.dispose();
        // 30 commands inside one window must not move the heartbeat either.
        ready();
        int beats=0;
        for(int i=1;i<=60;i++) {
            if(i%2==0) { can1Command(0xAA55,8,false,300); check(pass(),"ack in window"); }
            if(beat()) beats++;
        }
        check(beats==3,"heartbeat cadence survives 30 commands: "+beats);
        emu.dispose();
        println("PASS: acknowledgement and divider share 0x2253 without interference");
    }

    // 6. The lease field is the real countdown, little-endian, in 0.1 s units.
    void leaseField() throws Exception {
        ready(); can1Command(0xAA55,8,false,3000); pass();
        byte[] f=frame();
        check((f[8]&255)==0xB8 && (f[9]&255)==0x0B,"3000 ticks little-endian: got "
            +Integer.toHexString(f[8]&255)+" "+Integer.toHexString(f[9]&255));
        int previous=3000;
        for(int round=0;round<5;round++) {
            boolean did=false;
            for(int i=0;i<20;i++) { tick(); did=beat(); }
            check(did,"heartbeat on the 20th serviced tick");
            byte[] s=frame(); int reported=(s[8]&255)|((s[9]&255)<<8);
            check(reported==previous-20,"lease steps down by 20 per heartbeat: "+reported);
            assertStatus(s,"countdown round "+round); previous=reported;
        }
        emu.dispose();
        // Unarmed sessions report zero, never stale ticks.
        ready(); word(0x2254,0x1234); word(0x2250,0);
        check(beatAt(20),"heartbeat with no session");
        byte[] s=frame();
        check(s[8]==0 && s[9]==0 && (s[6]&1)==0,"no session: zero lease and clear armed bit");
        emu.dispose();
        println("PASS: lease field is the live PIT countdown, little-endian, zero when unarmed");
    }
    boolean beatAt(int ticks) throws Exception {
        boolean did=false; for(int i=0;i<ticks;i++) did=beat(); return did;
    }

    // 7. The recovery cutoff zeroes the lease and the magic in the same PIT tick,
    //    so the next frame is self-consistent. It is a timeout, not a command: no ack.
    void recoveryCutoff() throws Exception {
        ready(); byteAt(0x2652,5); word(0x233C,0); can1Command(0xAA55,8,false,3000);
        check(pass(),"ack for the arming command");
        word(0x233C,1000);                       // 100.0 C: far above charge L1 + 5 C
        tick();
        check(word(0x2254)==0 && word(0x2250)==0,"recovery cancels lease and magic together");
        check(pending()==0,"a timeout is not a command and arms no acknowledgement");
        check(beatAt(20),"next heartbeat");
        byte[] s=frame();
        check(s[8]==0 && s[9]==0,"lease reads zero after the cutoff");
        check((s[6]&1)==0,"armed bit clear after the cutoff");
        assertStatus(s,"after recovery cutoff");
        emu.dispose();
        println("PASS: recovery cutoff reported consistently and never acknowledged");
    }

    // 8. Over-temperature opens the relay while the lease keeps counting: the frame
    //    must show output off, inhibit set and a live countdown.
    void overTemperature() throws Exception {
        ready(); word(0x233E,500); byteAt(0x24E4,30);
        can1Command(0xAA55,8,false,3000); pass();
        byteAt(0x2256,1); byteAt(0x2350,byteAt(0x2350)&~4);
        check(beatAt(20),"heartbeat under inhibit");
        byte[] s=frame();
        check((s[5]&4)==0,"heater output reported off");
        check((s[6]&4)!=0,"over-temperature inhibit reported");
        check(((s[8]&255)|((s[9]&255)<<8))>0,"lease still counting under inhibit");
        check((s[6]&1)!=0,"session still armed under inhibit");
        assertStatus(s,"over-temperature");
        emu.dispose();
        println("PASS: inhibit distinguishes 'relay open' from 'session over'");
    }

    // 9. No free transmit buffer. The heartbeat simply misses its slot; the
    //    acknowledgement must survive, because losing it loses the only signal
    //    that a command was accepted.
    void noBuffer() throws Exception {
        ready();
        for(int i=1;i<20;i++) beat();
        bus(0); traced(()->nearCall(PERIODIC_TAIL),statusAllowed);
        check(!sent(frame()),"no frame when every buffer is busy");
        check(divider()==0,"the heartbeat slot is consumed, not retried immediately");
        for(int i=1;i<20;i++) check(!beat(),"waits a full period");
        check(beat(),"next heartbeat goes out");
        emu.dispose();
        ready(); can1Command(0xAA55,8,false,300);
        bus(0); traced(()->nearCall(FAST_TAIL),statusAllowed);
        check(!sent(frame()),"no acknowledgement frame without a buffer");
        check(pending()!=0,"acknowledgement re-armed after a failed transmit");
        check(pass(),"acknowledgement goes out on the following pass");
        assertStatus(frame(),"deferred acknowledgement");
        check(pending()==0,"request consumed once it is actually sent");
        emu.dispose();
        println("PASS: a full transmit buffer drops a heartbeat but never an acknowledgement");
    }

    // 10. A command arriving while the frame is being assembled must get its own
    //     frame. The receive ISR's only effect on this state is BSET 0x2253,#0x40,
    //     so injecting that bit at each instruction boundary models it exactly.
    void commandDuringAssembly() throws Exception {
        for(int at=0;at<40;at++) {
            ready(); can1Command(0xAA55,8,false,300); bus(1);
            int sp=reg("SP");
            emu.writeRegister("SP",sp-2);
            emu.writeMemory(ram(sp-2),new byte[]{0x40,0x10});
            emu.writeRegister("PC",0x7F0000L|FAST_TAIL);
            expectCode(0x7F5764,"165770","JSR send after the claim BCLR");
            boolean injected=false, claimed=false, afterClaim=false;
            for(int i=0;i<30000 && (pc()&65535)!=RETURN;i++) {
                if((pc()&65535)==0x5764) claimed=true;   // BCLR has executed
                if(i==at && !injected) {
                    byteAt(0x2253,byteAt(0x2253)|0x40); injected=true; afterClaim=claimed;
                }
                step();
            }
            check((pc()&65535)==RETURN,"hook completed with an injection at "+at);
            check(reg("SP")==sp,"stack balanced with an injection at "+at);
            check(sent(frame()),"the in-flight frame still went out at "+at);
            // Injected before the claim: the same bit the hook was already
            // servicing, so it is consumed. Injected after: a new command, and it
            // must survive to be acknowledged on the next pass.
            check((pending()!=0)==afterClaim,
                "request survives only when it arrived after the claim, at "+at);
            emu.dispose();
        }
        println("PASS: 40 command-arrival boundaries during assembly, none swallowed");
    }

    // 11. Non-interference with the stock CAN1 poll path: identical replies,
    //     identical bookkeeping, whether or not the broadcast runs in between.
    void nonInterference() throws Exception {
        int cases=0;
        for(int start=0;start<40;start+=7) for(int count:new int[]{1,3,4,8}) {
            if(start+count>40) continue;
            byte[] data=table(cases);
            byte[][] reply=new byte[2][];
            for(int variant=0;variant<2;variant++) {
                boot(true); byteAt(0x2253,19); byteAt(0x2416,0);
                emu.writeMemory(ram(0x23AE),data);
                byteAt(0x26DF,0); byteAt(0x26E1,0);
                request(0x1388+start,count,0x80);
                if(variant==1) check(beat(),"broadcast between request and reply");
                byte[] got=pacedSend();
                check(got!=null,"poll reply still sent");
                reply[variant]=got;
                check(word(0x26E2)==start+Math.min(4,count),"progress bookkeeping");
                check(byteAt(0x26DF)==(count>4?1:0),"busy bookkeeping");
                emu.dispose();
            }
            check(Arrays.equals(reply[0],reply[1]),
                "poll reply byte-identical with and without the broadcast, start="+start+" count="+count);
            cases++;
        }
        // The frame builder must not touch any CAN1 receiver/transmitter state.
        ready();
        byteAt(0x26DF,1); byteAt(0x26E1,1); word(0x26E2,7); word(0x26E4,3); word(0x26DB,0x8000);
        check(beatAt(20),"heartbeat");
        check(byteAt(0x26DF)==1 && byteAt(0x26E1)==1 && word(0x26E2)==7
            && word(0x26E4)==3 && word(0x26DB)==0x8000,"broadcast leaves poll bookkeeping alone");
        emu.dispose();
        println("PASS: "+cases+" poll sequences byte-identical with the broadcast interleaved");
    }

    // 12. Stack depth of both hooks, against the stock calls they replace.
    void stackDepth() throws Exception {
        ready(); bus(1);
        counters(); nearCall(SEND); int send=0x2200-minimumSP;
        ready(); byteAt(0x2253,19); bus(1);
        counters(); nearCall(PERIODIC_TAIL); int periodic=0x2200-minimumSP;
        check(sent(frame()),"the measured tick actually transmitted");
        ready(); can1Command(0xAA55,8,false,300); bus(1);
        counters(); nearCall(FAST_TAIL); int fast=0x2200-minimumSP;
        check(sent(frame()),"the measured pass actually transmitted");
        emu.dispose();
        // The stock CAN1 transmitter the fast hook now sits beside, measured the
        // same way. The two run in sequence, not nested, so a main-loop pass is
        // as deep as the deeper of them, not their sum.
        boot(true); emu.writeMemory(ram(0x23AE),table(1));
        byteAt(0x26DF,0); byteAt(0x26E1,0); request(0x1388,4,0x80);
        check(pacedSend()!=null,"stock reply for the depth comparison");
        int stock=0x2200-minimumSP;
        emu.dispose();
        println("MEASURE status frame builder: software stack bytes="+send);
        println("MEASURE periodic hook including its JSR frame: software stack bytes="+periodic);
        println("MEASURE fast hook including its JSR frame: software stack bytes="+fast);
        println("MEASURE stock CAN1 transmitter for comparison: software stack bytes="+stock);
        check(send==28 && periodic==30 && fast==30,
            "status stack depth unchanged: "+send+"/"+periodic+"/"+fast);
        check(fast<=stock,"a main-loop pass is no deeper than the stock transmitter it already ran: "
            +fast+" vs "+stock);
        println("PASS: status stack depth measured against the stock transmitter; "
            +"hardware interrupt frames excluded");
    }

    // 13. The splices themselves: stock CALL displaced into the hook, executed first.
    void splices() throws Exception {
        ready();
        for(int[] c:new int[][]{{FAST,0xAC93},{PERIODIC,0xA333}}) {
            emu.writeRegister("PC",0x7F0000L|c[0]);
            check(read(0x7F0000L|c[0])==0x4A,"hook begins with the displaced CALL");
            step();
            // Ghidra leaves a 16-bit PC for paged destinations; the page is in PPAGE.
            check((pc()&65535)==c[1],"displaced call reaches "+Integer.toHexString(c[1])
                +" got="+Long.toHexString(pc()));
            check(reg("PPAGE")==0xFE,"displaced call selects page FE");
        }
        // The dispatcher's tail is a JMP to the ack stub, three bytes for three.
        check(read(0x7F55B5)==0x06 && read(0x7F55B6)==0x57 && read(0x7F55B7)==0x38,
            "dispatcher tail is JMP ack_stub");
        check(read(0x7F55B8)==0xC7 && read(0x7F55B9)==0x3D,"not_cmd tail unchanged");
        emu.dispose();
        println("PASS: both splices execute the stock call they displaced, dispatcher tail is size neutral");
    }

    // Negative controls: break the mechanism in the emulator and prove the suite
    // notices. Each writes only to the emulator's memory; firmware is untouched.
    void negatives() throws Exception {
        rejects("a dispatcher tail that does not arm the status request",()->{
            ready(); expectCode(0x7F55B5,"06"+String.format("%04X",ACK),"dispatcher tail JMP ack_stub");
            emu.writeMemory(g(0x7F55B5),new byte[]{(byte)0xC6,0x01,0x3D}); // stock LDAB #1 / RTS
            can1Command(0xAA55,8,false,300);
            check(pending()!=0,"an accepted command must arm the status request");
            emu.dispose();
        });
        rejects("a divider that never reaches its period",()->{
            ready(); expectCode(0x7F574B,"C114","CMPB #20");
            emu.writeMemory(g(0x7F574C),new byte[]{(byte)0xFF});           // CMPB #20 -> CMPB #255
            boolean any=false; for(int i=1;i<=40;i++) if(beat()) any=true;
            check(any,"a heartbeat must occur within two periods");
            emu.dispose();
        });
        rejects("an acknowledgement dropped when every buffer is busy",()->{
            ready(); expectCode(0x7F576B,"1C225340","re-arm BSET");
            emu.writeMemory(g(0x7F576B),new byte[]{(byte)0xA7,(byte)0xA7,(byte)0xA7,(byte)0xA7}); // drop the re-arm
            can1Command(0xAA55,8,false,300);
            bus(0); traced(()->nearCall(FAST_TAIL),statusAllowed);
            check(pending()!=0,"the request must survive a failed transmit");
            emu.dispose();
        });
        rejects("a frame builder that reports a stale lease",()->{
            ready(); expectCode(0x7F5788,"8CAA55260D","CPD #AA55 / BNE no_lease");
            emu.writeMemory(g(0x7F578B),new byte[]{(byte)0xA7,(byte)0xA7});  // drop the magic gate
            word(0x2254,0x1234); word(0x2250,0);
            check(beatAt(20),"heartbeat");
            byte[] s=frame();
            check(((s[8]&255)|((s[9]&255)<<8))==0,"an unarmed session must report a zero lease");
            emu.dispose();
        });
    }

    @Override public void run() throws Exception {
        loadImage();
        splices(); startupZeroFill(); cadence(); acknowledges(); rejectsSilently(); sharedByte();
        leaseField(); recoveryCutoff(); overTemperature(); noBuffer();
        commandDuringAssembly(); nonInterference(); stackDepth(); negatives();
        println("STATUS BROADCAST ACCEPTANCE PASSED: "+frames+" frames transmitted, "
            +assertions+" assertions");
    }
}
