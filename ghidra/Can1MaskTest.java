// Combined heater/CAN1 mask acceptance. Args: patched global S19, stock global S19
// [, 1392 low-byte mask in hex: 3E by default, 16 for a --diffmask image].
// Executes the real CAN4 receiver, CAN1 receiver, CAN1 sender and MSCAN driver.
// MMIO is a RAM fixture, not a model of arbitration or physical bus timing.
//@category Test
import java.util.*;
import ghidra.app.emulator.EmulatorHelper;

public class Can1MaskTest extends PreFlashExtraTests {
    final int[] txAllowed={0x186,0x18A,0x1B0,0x1B1,0x1B2,0x1B3,0x1B4,0x1B5,0x1B6,0x1B7,
        0x1B8,0x1B9,0x1BA,0x1BB,0x1BC,0x26DF,0x26E1,0x26E2,0x26E3,0x26E4,0x26E5};
    byte[] heaterData(int ticks) {return new byte[]{(byte)0xAA,0x55,0,0,0,0,(byte)(ticks>>8),(byte)ticks};}
    byte[] leaseData(int ticks) {return new byte[]{0x4D,0x53,0,0,0,0,(byte)(ticks>>8),(byte)ticks};}
    byte[] toggleData(int value) {
        byte[] data=leaseData(value==1?100:0);
        if(value>1)data[2]=(byte)value; // reserved-byte rejection fixtures
        return data;
    }
    // Both commands now arrive on CAN1 as RV-C proprietary DGN 0x18EF70xx.
    byte[] toggleBody; int toggleId,toggleDlc; boolean toggleExt,toggleRtr;
    void toggleFrame(byte[] data) throws Exception {
        toggleBody=data; toggleId=0x18EF7080; toggleExt=true; toggleDlc=8; toggleRtr=false;
    }
    void deliver() throws Exception { can1Frame(toggleId,toggleExt,toggleDlc,toggleRtr,toggleBody); }
    void toggle(int value) throws Exception {toggleFrame(toggleData(value));deliver();}
    void maskLease(int ticks) throws Exception {toggleFrame(leaseData(ticks));deliver();}
    void accepted(byte[] data,int expected) throws Exception {
        int magic=word(0x2250),lease=word(0x2254),port=read(0),object=byteAt(0x2350);
        int remaining=word(0x240E);
        boolean valid=(word(0x2BEC)==3||word(0x2BEC)==4) && data[0]==0x4D && data[1]==0x53
            && data[2]==0 && data[3]==0 && data[4]==0 && data[5]==0;
        toggleFrame(data);
        traced(()->deliver(),valid?new int[]{0x2252,0x240E,0x240F,0x184}:rejectAllowed);
        check(byteAt(0x2252)==expected,"toggle payload "+HexFormat.of().formatHex(data));
        check(word(0x240E)==(valid?((data[6]&255)<<8)|(data[7]&255):remaining),"mask lease publication/rejection");
        check(word(0x2250)==magic && word(0x2254)==lease && read(0)==port && byteAt(0x2350)==object,
            "toggle must not touch heater state");
    }
    void commands() throws Exception {
        init();command(1234);
        for(int old:new int[]{0,0xA5}) for(int v=0;v<256;v++) {
            byteAt(0x2252,old);accepted(toggleData(v),v<2?(v==1?0xA5:0):old);
        }
        for(int old:new int[]{0,0xA5}) for(int v=0;v<2;v++) for(int index=2;index<6;index++)
            for(int bit=0;bit<8;bit++) {
                byteAt(0x2252,old);byte[] data=toggleData(v);data[index]=(byte)(1<<bit);accepted(data,old);
            }
        for(int old:new int[]{0,0xA5}) for(int bit=0;bit<16;bit++) {
            byteAt(0x2252,old);byte[] data=toggleData(1);data[bit/8]^=(byte)(1<<(bit%8));accepted(data,old);
        }
        for(int old:new int[]{0,0xA5}) for(int dlc=0;dlc<16;dlc++) for(boolean rtr:new boolean[]{false,true}) {
            byteAt(0x2252,old);word(0x240E,0x1234);toggleFrame(toggleData(old==0?1:0));
            toggleDlc=dlc; toggleRtr=rtr;
            traced(()->deliver(),dlc==8&&!rtr?new int[]{0x2252,0x240E,0x240F,0x184}:rejectAllowed);
            check(byteAt(0x2252)==(dlc==8&&!rtr?(old==0?0xA5:0):old),"DLC/RTR validation");
            check(word(0x240E)==(dlc==8&&!rtr?(old==0?100:0):0x1234),"DLC/RTR cannot renew or cancel lease");
        }
        for(int state=0;state<6;state++) for(int page:new int[]{0xFC,0xFD,0xFE,0xFF}) {
            byteAt(0x2252,0);word(0x2BEC,state);emu.writeRegister("PPAGE",page);
            accepted(toggleData(1),state==3||state==4?0xA5:0);
        }
        word(0x2BEC,3);
        // Neighbouring DGNs, the wrong destination address, and the priority-7
        // form the specification would default to: none of them may be decoded.
        for(int id:new int[]{0x199,0x18EE7080,0x18EF6F80,0x18EF7180,0x1CEF7080})
            for(boolean extended:new boolean[]{false,true}) {
            if(!extended && id>0x7FF) continue;
            byteAt(0x2252,0);toggleFrame(toggleData(1));toggleId=id;toggleExt=extended;
            traced(()->deliver(),rejectAllowed);check(byteAt(0x2252)==0,"wrong ID ignored");
        }
        for(int old:new int[]{0,0xA5}) {
            byteAt(0x2252,old);word(0x240E,100);command(9);tick();check(word(0x2254)==8,"heater timer retained");
            command(0);check(byteAt(0x2252)==old,"heater commands never change toggle");
        }
        emu.dispose();
        init();byteAt(0x2252,0xA5);word(0x240E,65535);word(0x2250,0xAA55);word(0x2254,999);
        emu.writeRegister("PC",0x7F4000);runTo(0x400B,50000);
        check(byteAt(0x2252)==0x0C,"actual startup restores stock 0C (mask disabled)");
        check(word(0x240E)==0,"actual startup clears mask timer");
        check(word(0x2250)==0x028A && word(0x2254)==0x3232,"original heater reset initializers");
        emu.dispose();println("PASS: mask command values, reserved bits, magic, DLC/RTR, state/page gates, wrong IDs, heater independence, actual startup default");
        println("CAN1 MASK COMMAND REJECTION ACCEPTANCE PASSED");
    }
    byte[] table(int seed) {
        byte[] bytes=new byte[80];new Random(seed).nextBytes(bytes);return bytes;
    }
    int maskLow=0x3E;
    byte[] expected(byte[] table,int index,int words,boolean mask) {
        byte[] out=new byte[8];
        for(int i=0;i<words*2;i++) {
            int at=index*2+i,v=table[at]&255;
            if(mask) {if(at==20)v&=0xEB; if(at==21)v&=maskLow; if(at==22)v&=0xFE;}
            out[i]=(byte)v;
        }
        return out;
    }
    void txFixture(byte[] table,int index,int words,int enabled,int source) throws Exception {
        emu.writeRegister("SP",0x2200);emu.writeRegister("PPAGE",0xFC);emu.writeRegister("CCR",0);
        word(0x2622,0x180);word(0x26DB,source<<8);byteAt(0x26DF,1);byteAt(0x26E1,1);
        word(0x26E2,index);word(0x26E4,words);byteAt(0x2252,enabled);word(0x240E,100);
        emu.writeMemory(ram(0x23AE),table);emu.writeMemory(g(0x186),new byte[]{7});
        byte[] poison=new byte[13];Arrays.fill(poison,(byte)0xCC);emu.writeMemory(g(0x1B0),poison);
    }
    byte[] send() throws Exception {
        counters();traced(()->{farEntry(0xAC93);runTo(0x4010,10000);},txAllowed);
        check(reg("SP")==0x2200 && reg("PPAGE")==0xFC,"TX stack/page restoration");
        return emu.readMemory(g(0x1B0),13);
    }
    void assertFrame(byte[] actual,byte[] expected,int words,int source) {
        check(Arrays.equals(Arrays.copyOf(actual,4),encoded(0x18EF0070|(source<<8),true,false)),"CAN1 exact response ID");
        check(Arrays.equals(Arrays.copyOfRange(actual,4,12),expected),"CAN1 exact data/padding");
        check((actual[12]&255)==words*2,"CAN1 exact DLC");
    }
    void transmissions() throws Exception {
        init();patched=emu;init();original=emu;
        restoreStock(original,0x7FACD3,0x7FAD2B);restoreStock(original,0x7FAD8D,0x7FADCE);
        restoreStock(original,0x7FAD5F,0x7FAD6D);
        int cases=0;
        for(int index=0;index<40;index++) for(int words=1;words<=Math.min(4,40-index);words++)
            for(int enabled:new int[]{0,0xA5}) {
                byte[] data=table(cases);int source=(cases*73)&255;
                emu=original;txFixture(data,index,words,enabled,source);byte[] stock=send();
                emu=patched;txFixture(data,index,words,enabled,source);byte[] actual=send();
                assertFrame(actual,expected(data,index,words,enabled==0xA5),words,source);
                if(enabled==0)check(Arrays.equals(stock,actual),"disabled equals stock TX bytes");
                check(Arrays.equals(emu.readMemory(ram(0x23AE),80),data),"telemetry source untouched");
                check(word(0x26E2)==index+words && word(0x26E4)==0 && byteAt(0x26E1)==0,"progress bookkeeping");
                check(byteAt(0x26DF)==0,"busy released with the final frame");
                emu=original;check(byteAt(0x26DF)==(words==4?1:0),"stock completion bookkeeping");emu=patched;cases++;
            }
        byte[] all=new byte[80];Arrays.fill(all,(byte)255);
        for(int flag=0;flag<256;flag++) {
            txFixture(all,10,4,flag,0x80);assertFrame(send(),expected(all,10,4,flag==0xA5),4,0x80);
        }
        // Every bit in all three alarm/status words, including all L2 bits.
        for(int wordIndex=10;wordIndex<=12;wordIndex++) for(int bit=0;bit<16;bit++) {
            byte[] one=new byte[80];one[2*wordIndex]=(byte)((1<<bit)>>8);one[2*wordIndex+1]=(byte)(1<<bit);
            txFixture(one,10,3,0xA5,0x80);assertFrame(send(),expected(one,10,3,true),3,0x80);
        }
        original.dispose();patched.dispose();
        println("PASS: "+cases+" real-driver full/partial windows, stock differential when disabled, every enable-byte value, every bit of 1392..1394, source RAM and TX bookkeeping");
    }
    void can1Command(int id,boolean ext,int dlc,boolean rtr,byte[] data) throws Exception {
        can1Frame(id,ext,dlc,rtr,data);   // asserts the receiver's stack/mask/page contract
    }
    // The same two commands delivered as an RV-C proprietary DGN on CAN1.
    void can1Commands() throws Exception {
        init();emu.writeRegister("PPAGE",0xFC);word(0x2622,0x180);int cases=0;
        for(int ticks:new int[]{1,100,600,65535}) for(int source:new int[]{0x01,0x80,0xF9,0xFF}) {
            byteAt(0x2252,0);word(0x240E,0);word(0x2250,0);word(0x2254,0);byteAt(0x26DF,0);
            can1Command(0x18EF7000|source,true,8,false,leaseData(ticks));
            check(byteAt(0x2252)==0xA5 && word(0x240E)==ticks,"CAN1 mask lease armed from any source address");
            check(word(0x2250)==0 && word(0x2254)==0,"CAN1 mask command leaves the heater alone");
            byteAt(0x2252,0);word(0x240E,0);
            can1Command(0x18EF7000|source,true,8,false,heaterData(ticks));
            check(word(0x2250)==0xAA55 && word(0x2254)==ticks,"CAN1 heater lease armed");
            check(byteAt(0x2252)==0 && word(0x240E)==0,"CAN1 heater command leaves the mask alone");
            cases+=2;
        }
        for(int index=2;index<6;index++) for(int bit=0;bit<8;bit++) {
            byteAt(0x2252,0);word(0x240E,0x1234);byteAt(0x26DF,0);
            byte[] data=leaseData(100);data[index]=(byte)(1<<bit);
            can1Command(0x18EF70F9,true,8,false,data);
            check(byteAt(0x2252)==0 && word(0x240E)==0x1234,"CAN1 reserved-byte rejection");cases++;
        }
        // A command is accepted while a poll is outstanding, and never disturbs it.
        for(int busy:new int[]{0,1}) {
            byteAt(0x2252,0);word(0x240E,0);byteAt(0x26DF,busy);
            word(0x26E2,7);word(0x26E4,3);byteAt(0x26DB,0x80);
            can1Command(0x18EF70F9,true,8,false,leaseData(50));
            check(byteAt(0x2252)==0xA5 && word(0x240E)==50,"CAN1 command accepted regardless of busy");
            check(byteAt(0x26DF)==busy && word(0x26E2)==7 && word(0x26E4)==3 && byteAt(0x26DB)==0x80,
                "CAN1 command preserves an outstanding request");cases++;
        }
        // The CAN1 dispatcher now applies the system-state gate the stock CAN4
        // dispatcher applied at 7f4e39: commands are admitted only in state 3
        // or 4. Without it a lease armed while the BMS was still booting kept
        // counting and fired on entry to state 3.
        for(int state=0;state<6;state++) {
            boolean running=state==3||state==4;
            word(0x2BEC,state);byteAt(0x2252,0);word(0x240E,0);word(0x2250,0);word(0x2254,0);byteAt(0x26DF,0);
            can1Command(0x18EF70F9,true,8,false,leaseData(100));
            check(byteAt(0x2252)==(running?0xA5:0) && word(0x240E)==(running?100:0),
                "CAN1 mask command admitted only in state 3 or 4");
            can1Command(0x18EF70F9,true,8,false,heaterData(100));
            check(word(0x2250)==(running?0xAA55:0) && word(0x2254)==(running?100:0),
                "CAN1 heater command admitted only in state 3 or 4");
            // A rejected command never reaches the output stage, so reconcile
            // explicitly rather than relying on the command to do it.
            output();
            check(state==3 || (read(0)&8)==0,"manual heating outside state 3 stays off");
            cases+=2;
        }
        word(0x2BEC,3);
        byteAt(0x26DF,0);request(0x1392,3,0x80);
        check(word(0x26E2)==10 && word(0x26E4)==3 && byteAt(0x26DF)==1,"register request still decoded after the hook");
        emu.dispose();
        println("PASS: "+cases+" CAN1 proprietary-DGN command cases: both magics, any source address, "
            +"reserved-byte rejection, acceptance while a poll is outstanding, register path intact");
    }
    void request(int address,int count,int source) throws Exception {
        String[] names={"PC","SP","D","IX","IY","CCR","PPAGE","RPAGE","EPAGE"};
        long[] saved=new long[names.length];
        for(int i=0;i<names.length;i++)saved[i]=emu.readRegister(names[i]).longValue();
        emu.writeMemory(g(0x1A0),encoded(0x18EF7000|source,true,false));
        emu.writeMemory(g(0x1A4),new byte[]{(byte)(address>>8),(byte)address,(byte)(count>>8),(byte)count,-1,-1,-1,-1});
        emu.writeMemory(g(0x1AC),new byte[]{8});
        emu.writeRegister("SP",reg("SP")-16);emu.writeRegister("CCR",reg("CCR")|16);
        emu.writeRegister("PC",0x7F4BDA);runTo(0x4D41,5000);
        check(reg("SP")==saved[1]-16-18,"CAN1 RX stack");
        for(int i=0;i<names.length;i++)emu.writeRegister(names[i],saved[i]);
    }
    void transactions() throws Exception {
        init();emu.writeRegister("PPAGE",0xFC);word(0x2622,0x180);int cases=0,frames=0;
        // All valid table requests, not just aligned four-word reads. RX creates
        // the real index/count; TX runs repeatedly until the stock busy flag clears.
        for(int start=0;start<40;start++) for(int count=0;count<=40-start;count++)
            for(boolean masked:new boolean[]{false,true}) {
                byte[] data=table(cases);emu.writeMemory(ram(0x23AE),data);
                byteAt(0x2252,masked?0xA5:0x0C);byteAt(0x26DF,0);
                int source=(cases*17)&255;request(0x1388+start,count,source);
                check(word(0x26E2)==start && word(0x26E4)==count && byteAt(0x26DF)==1,"real request accepted");
                int sent=0;
                for(int step=0;byteAt(0x26DF)!=0;step++) {
                    check(step<12,"multi-frame request completes");
                    byteAt(0x26E1,1);emu.writeMemory(g(0x186),new byte[]{7});
                    int words=Math.min(4,count-sent);byte[] actual=send();
                    if(words>0){assertFrame(actual,expected(data,start+sent,words,masked),words,source);frames++;}
                    sent+=words;
                }
                check(sent==count && word(0x26E4)==0,"all requested words sent exactly once");cases++;
            }
        // Reproduce the user's model request using bytes generated by stock code.
        emu.writeRegister("PPAGE",0xFE);emu.writeRegister("PC",0x7FAC5E);runTo(0xAC8E,100);
        emu.writeRegister("PPAGE",0xFC);
        byteAt(0x26DF,0);request(0x1402,8,0x80);
        check(word(0x26E2)==31 && word(0x26E4)==8,"1402 model alias");
        byteAt(0x26E1,1);emu.writeMemory(g(0x186),new byte[]{7});
        byte[] model=send();
        check(HexFormat.of().formatHex(Arrays.copyOfRange(model,4,12)).equals("5242543231304c46"),"observed model reply reproduced");
        // CAN1 does not interpret the new CAN4 toggle payload as a command.
        byteAt(0x2252,0);byteAt(0x26DF,0);emu.writeMemory(g(0x1A0),encoded(0x999,true,false));
        emu.writeMemory(g(0x1A4),toggleData(1));emu.writeRegister("PC",0x7F4BDA);
        runTo(0x4D41,5000);check(byteAt(0x2252)==0,"toggle on wrong bus ignored");
        emu.dispose();println("PASS: "+cases+" real CAN1 requests / "+frames+" frames, all valid table ranges and counts, model alias and observed reply, CAN bus isolation");
    }
    void retries() throws Exception {
        init();byte[] data=new byte[80];Arrays.fill(data,(byte)255);
        for(int count:new int[]{1,2,3,4,5,40}) for(int blocked=0;blocked<4;blocked++) {
            txFixture(data,0,count,0xA5,0x80);
            if(blocked==0)byteAt(0x26DF,0);
            if(blocked==1)byteAt(0x26E1,0);
            if(blocked==2)emu.writeMemory(g(0x186),new byte[]{0});
            if(blocked==3) {
                farEntry(0xAC93);runTo(count<4?0xAD8D:0xACD3,1000);
                emu.writeMemory(g(0x186),new byte[]{0}); // first check passed, driver's check fails
                // The buffer is in the already allocated caller frame above
                // current SP; keep the real pre-call stack ceiling for tracing.
                Trace trace=new Trace(emu,0x2200);trace.heaterPolicy(txAllowed);
                try {runTo(0x4010,10000);} finally {trace.dispose();}
            } else send();
            check(word(0x26E2)==0 && word(0x26E4)==count,"blocked send retains index/count");
            check(read(0x1B0)==0xCC && read(0x1BC)==0xCC,"blocked send writes no frame");
            byteAt(0x26DF,1);byteAt(0x26E1,1);emu.writeMemory(g(0x186),new byte[]{7});
            assertFrame(send(),expected(data,0,Math.min(4,count),true),Math.min(4,count),0x80);
        }
        // A failed send is rebuilt using the current toggle on retry.
        txFixture(data,10,3,0,0x80);farEntry(0xAC93);runTo(0xADCE,1000);
        emu.writeMemory(g(0x186),new byte[]{0});runTo(0x4010,1000);toggle(1);
        emu.writeMemory(g(0x186),new byte[]{7});assertFrame(send(),expected(data,10,3,true),3,0x80);
        emu.dispose();println("PASS: idle/pacing/busy and driver-race failures retain requests; retries rebuild data using current toggle");
    }
    void interleavings() throws Exception {
        byte[] data=new byte[80];Arrays.fill(data,(byte)255);int cases=0,maxDepth=0;
        for(boolean expiry:new boolean[]{false,true})
        for(int count:new int[]{2,3,4}) for(int old:new int[]{0,0xA5}) {
            if(expiry && old==0)continue;
            init();txFixture(data,10,count,old,0x80);farEntry(0xAC93);
            int join=count==4?0xAD2B:0xADCE;
            runTo(count==4?0xACD3:0xAD8D,1000);
            ArrayList<Integer> path=new ArrayList<>();
            while((pc()&65535)!=join){path.add((int)(pc()&65535));step();}
            path.add(join);emu.dispose();
            for(int boundary=0;boundary<path.size();boundary++) {
                init();txFixture(data,10,count,old,0x80);farEntry(0xAC93);
                if(expiry)word(0x240E,1);
                runTo(count==4?0xACD3:0xAD8D,1000);boolean sampled=false;
                for(int i=0;i<boundary;i++) {
                    long at=global((int)pc());
                    if(read(at)==0xB6 && read(at+1)==0x22 && read(at+2)==0x52)sampled=true;
                    step();
                }
                counters();int top=reg("SP");
                if(expiry)interruptTick();else toggle(old==0?1:0);
                maxDepth=Math.max(maxDepth,top-minimumSP);
                runTo(0x4010,10000);
                assertFrame(emu.readMemory(g(0x1B0),13),expected(data,10,count,sampled?old==0xA5:old==0),count,0x80);
                check(reg("SP")==0x2200 && reg("PPAGE")==0xFC,"interrupt during copy stack/page");
                check(Arrays.equals(emu.readMemory(ram(0x23AE),80),data),"interrupt leaves source intact");
                emu.dispose();cases++;
            }
        }
        println("PASS: "+cases+" toggle/expiry IRQ interleavings across full/partial copy; one consistent mode per frame; modeled nested stack bytes="+maxDepth);
    }
    void leases() throws Exception {
        final int[] maskTickAllowed={0,0x2250,0x2251,0x2252,0x2254,0x2255,0x2256,
            0x24E4,0x232C,0x2350,0x240E,0x240F};
        init();word(0x2410,0xC35A);byteAt(0x2253,0xE4);
        // Execute the real PIT hook for every nonzero 16-bit counter value.
        // The main task never runs in this loop; lease expiry cannot depend on it.
        Trace trace=new Trace(emu,0x2200);trace.heaterPolicy(maskTickAllowed);
        try {
            for(int remaining=1;remaining<=65535;remaining++) {
                word(0x240E,remaining);byteAt(0x2252,0xA5);byteAt(0x232C,0xA1);
                tick();
                check(word(0x240E)==remaining-1,"unsigned mask countdown "+remaining);
                check(byteAt(0x2252)==(remaining==1?0:0xA5),"mask expiry boundary "+remaining);
                check(byteAt(0x232C)==0xA5,"original PIT task flag replayed");
                check(word(0x2410)==0xC35A && byteAt(0x2253)==0xE4,"timer preserves adjacent alarm/scratch");
                trace.effects.clear();
            }
            for(int flag=0;flag<256;flag++) if(flag!=0xA5) for(int count:new int[]{0,1,65535}) {
                byteAt(0x2252,flag);word(0x240E,count);tick();
                check(byteAt(0x2252)==flag && word(0x240E)==count,"disabled timer cannot underflow or arm");
            }
        } finally {trace.dispose();emu.dispose();}
        for(int state=0;state<6;state++) for(int page:new int[]{0xFC,0xFD,0xFE,0xFF}) {
            init();maskLease(2);word(0x2BEC,state);emu.writeRegister("PPAGE",page);
            traced(()->tick(),maskTickAllowed);check(word(0x240E)==1 && byteAt(0x2252)==0xA5,"mask counts in every state/page");
            traced(()->tick(),maskTickAllowed);check(word(0x240E)==0 && byteAt(0x2252)==0,"mask expires in every state/page");
            tick();check(word(0x240E)==0 && byteAt(0x2252)==0,"expired timer stays off");emu.dispose();
        }
        init();
        for(int value:new int[]{0,1,2,255,256,257,32767,32768,65534,65535}) {
            accepted(leaseData(value),value==0?0:0xA5);
            check(word(0x240E)==value,"lease command byte order/full range");
        }
        accepted(new byte[]{0x4D,0x53,1,0,0,0,0,0},0xA5); // old unlimited-ON format rejected
        maskLease(3);tick();maskLease(5);check(word(0x240E)==5,"renewal replaces remaining time");
        for(int i=0;i<4;i++)tick();check(byteAt(0x2252)==0xA5,"renewal remains on before expiry");
        tick();check(byteAt(0x2252)==0 && word(0x240E)==0,"renewal expires exactly");
        maskLease(100);maskLease(1);tick();check(byteAt(0x2252)==0,"shorter renewal replaces lease");
        maskLease(65535);maskLease(0);tick();check(word(0x240E)==0 && byteAt(0x2252)==0,"explicit zero cancels immediately");
        command(7);maskLease(3);for(int i=0;i<3;i++)tick();
        check(word(0x2254)==4 && word(0x240E)==0 && byteAt(0x2252)==0,"mask expiry leaves heater lease active");
        relay(true,"mask expiry preserves manual heater");
        maskLease(10);command(1);tick();
        check(word(0x2254)==0 && word(0x240E)==9 && byteAt(0x2252)==0xA5,"heater expiry leaves mask active");
        command(100);word(0x233C,100);tick();
        check(word(0x2254)==0 && word(0x240E)==8 && byteAt(0x2252)==0xA5,"recovery cutoff leaves mask counting");
        // Validate normal CAN1 data after real expiry, without resetting the flag.
        word(0x233C,0);maskLease(1);tick();
        byte[] data=new byte[80];Arrays.fill(data,(byte)255);
        int flag=byteAt(0x2252);txFixture(data,10,3,flag,0x80);
        assertFrame(send(),expected(data,10,3,false),3,0x80);
        emu.dispose();
        println("PASS: all 65535 positive counters, 765 disabled counter states, state/page expiry, renewal/shortening/OFF, paused main, independent heater timers and real unmasked TX after expiry");
        println("CAN1 MASK LEASE ACCEPTANCE PASSED");
    }
    void producers() throws Exception {
        init();
        for(int mode:new int[]{1,3}) for(int level=0;level<8;level++) {
            byteAt(0x2417,level);byteAt(0x2419,level);byteAt(0x2416,level);
            byteAt(0x2350,4);word(0x2BE6,mode);byteAt(0x2252,0xA5);
            emu.writeRegister("PPAGE",0xFE);emu.writeRegister("PC",0x7FA985);runTo(0xAC16,3000);
            boolean l1=(level&1)!=0,l2=(level&2)!=0;
            check(((word(0x23C2)&0x0400)!=0)==l1,"1392 cell UV L1 source");
            check(((word(0x23C4)&0x0100)!=0)==l1,"1393 duplicate cell UV L1 source");
            check(((word(0x23C6)&0x0100)!=0)==l2,"1394 cell UV L2 source");
            check(((word(0x23C2)&0x1000)!=0)==l1,"1392 pack UV L1 source");
            check(((word(0x23C2)&(mode==1?0x0080:0x0040))!=0)==l1,"mode-specific cold L1");
            check((word(0x23C2)&1)!=0,"heater logical output status");
            byte[] data=emu.readMemory(ram(0x23AE),80);
            txFixture(data,10,3,0xA5,0x80);assertFrame(send(),expected(data,10,3,true),3,0x80);
            check(byteAt(0x2417)==level && byteAt(0x2419)==level && byteAt(0x2416)==level && byteAt(0x2350)==4,
                "real producer plus masked transmission preserves alarm and relay objects");
        }
        for(int enabled:new int[]{0,0xA5}) {
            byteAt(0x2252,enabled);byteAt(0x2350,0);word(0x233C,99);command(300);
            toggle(enabled==0?1:0);check(word(0x2254)==300,"toggling keeps live heater lease");
            int flag=byteAt(0x2252);word(0x233C,100);tick();
            check(word(0x2254)==0 && byteAt(0x2252)==flag,"recovery cutoff preserves mask setting");
            relay(false,"mask cannot defeat heater recovery cutoff");
        }
        emu.dispose();println("PASS: real alarm producer confirms both cell UV L1 bits, cold mode mapping and preserved L2/relay objects; recovery cutoff independent of toggle");
    }
    void maskMutations() throws Exception {
        byte[] data=new byte[80];Arrays.fill(data,(byte)255);
        init();emu.writeMemory(g(0x7FACFD),new byte[]{(byte)255});
        txFixture(data,10,3,0xA5,0x80);
        rejects("CAN1 incorrect mask",()->assertFrame(send(),expected(data,10,3,true),3,0x80));emu.dispose();
        init();emu.writeMemory(g(0x7FACE9),new byte[]{0x0C});
        txFixture(data,10,3,0x0C,0x80);
        rejects("CAN1 masking enabled at boot",()->assertFrame(send(),expected(data,10,3,false),3,0x80));emu.dispose();
        init();emu.writeMemory(g(0x7FAD26),new byte[]{(byte)0xA7,(byte)0xA7,(byte)0xA7});
        maskLease(1);
        rejects("CAN1 lease does not disarm on expiry",()->{tick();check(byteAt(0x2252)==0,"expiry must disarm");});emu.dispose();
    }
    @Override public void run() throws Exception {
        loadImage();
        if(getScriptArgs().length>=3 && !getScriptArgs()[2].startsWith("--")) {
            maskLow=Integer.parseInt(getScriptArgs()[2],16);
            println("1392 low-byte mask expected: "+String.format("%02X",maskLow));
        }
        if(getScriptArgs().length==3 && getScriptArgs()[2].equals("--commands-only")) {
            commands();return;
        }
        for(long entry:new long[]{0x7FACD3,0x7FAD8D,0x7FAD97,0x7FADB7}) disassemble(g(entry));
        if(Arrays.asList(getScriptArgs()).contains("--copy-only")) {
            // Images from the status broadcast on acknowledge each lease command
            // (a write the command fixtures forbid), and the uvcharge image hides
            // 1393 0100 at its source (producers() expects stock); the copy paths
            // are unchanged.
            transmissions();transactions();retries();interleavings();maskMutations();
            println("CAN1 MASK COPY ACCEPTANCE PASSED");return;
        }
        leases();commands();can1Commands();transmissions();transactions();retries();interleavings();producers();maskMutations();
        println("CAN1 MASK ACCEPTANCE PASSED");
    }
}
