// Strengthened pre-flash acceptance. Args: patched global S19, stock global S19.
// No hardware access; counts are instructions, not cycle/latency guarantees.
//@category Test
import ghidra.app.emulator.*;
import java.util.*;

public class PreFlashExtraTests extends VerifiedHeaterHarness {
    record Outcome(byte[] ram, List<String> effects, Set<Integer> path, int count) {}
    byte[] payload(int variant) {
        byte[][] p={{(byte)0xAA,(byte)0xAA,1,2,3,4,5,6},
            {(byte)0xAA,(byte)0xAA,2,9,8,7,6,5},
            {0,0,77,1,0,42,1,2},
            {(byte)0xFF,(byte)0xFF,(byte)0xFF,3,4,5,6,7},
            {4,1,2,3,4,5,6,0},{0,1,2,3,4,5,6,0}};
        byte[] r=p[variant].clone();
        if(variant>=4) {int sum=0;for(int i=0;i<7;i++)sum+=r[i]&255;r[7]=(byte)sum;}
        return r;
    }
    Outcome handler(EmulatorHelper h,int index,boolean ext,int state,int variant,boolean poison) throws Exception {
        emu=h; emu.writeMemory(ram(0x2000),new byte[0x2000]); emu.writeMemory(g(0),new byte[0x800]);
        reset(h,encoded(ids[index],ext,false)); word(0x2BEC,state);
        byte[] p=payload(variant); emu.writeMemory(g(0x2A4),p); emu.writeMemory(g(0x2AC),new byte[]{8});
        byteAt(0x2680,variant&1); // exercise 0x311 gated SoC recalculation
        word(0x2376,0x42C8); word(0x2378,0); // float capacity=100
        word(0x2BF0,0x2C06); byteAt(0x2C04,1); // real settings dirty-object match
        runTo(0x4E39,1000);
        Trace trace=new Trace(h,0x2200);
        if(poison) for(int offset:new int[]{4,5,6,7,10,13,14,15,16}) {
            long address=RAM+0x21E9+offset;
            emu.writeMemory(g(address),new byte[]{(byte)(0xC3^offset^variant)});
            trace.poison.add(address);
        }
        counters();
        try {
            runTo(0x55D5,20000);
            check(visited.contains(entries[index]),"intended handler reached");
            check(reg("SP")==0x21E9 && (reg("CCR")&16)!=0 && reg("PPAGE")==0xFC,"handler stack/mask/page");
            int id=ids[index];
            if(id>=0x410 && id<=0x41A) {
                check(Arrays.equals(emu.readMemory(ram(0x2626+(id-0x410)*8),8),p),"settings payload copied");
                if(id<=0x416) check((byteAt(0x2C0A)&1)!=0,"settings object marked dirty");
            }
            if(id==0x20F && variant<2) {
                long actual=((long)word(0x236E)<<16)|word(0x2370);
                check(actual==(Float.floatToIntBits(p[2]&255)&0xFFFFFFFFL),"SoC conversion executed");
            }
            if(id==0x500 && variant<2) check((byteAt(variant==0?0x2898:0x276A)&1)!=0,"settings dump flag set");
            if(id==0x311) check(byteAt(0x2382)==(variant&1),"0x311 conditional path");
            if(id==0x300 && variant>=4) check(visited.contains(0x5143)==(variant==4),"0x300 BRSET checksum branch");
            byte[] memory=emu.readMemory(ram(0x2000),0x2000);
            // Compare persistent state; exact current ISR stack is excluded.
            Arrays.fill(memory,minimumSP-0x2000,0x2200-0x2000,(byte)0);
            return new Outcome(memory,new ArrayList<>(trace.effects),new HashSet<>(visited),instructionCount);
        } finally {trace.dispose();}
    }
    void handlers() throws Exception {
        pair(); int count=0;
        for(int i=0;i<ids.length;i++) {
            if(ids[i]==0x11||ids[i]==0x999||ids[i]==0x08F62DE2) continue;
            for(boolean ext:new boolean[]{false,true}) for(int state:new int[]{3,4}) for(int variant=0;variant<6;variant++) {
                Outcome a=handler(original,i,ext,state,variant,false);
                Outcome b=handler(patched,i,ext,state,variant,true);
                check(Arrays.equals(a.ram,b.ram),"persistent handler state differs id="+Integer.toHexString(ids[i]));
                check(a.effects.equals(b.effects),"ordered MMIO/RAM effects differ");
                check(a.path.equals(b.path)&&a.count==b.count,"handler control flow differs"); count++;
            }
        }
        // Make a tested handler hang: completion MUST fail.
        patched.writeMemory(g(0x7F4FDB),new byte[]{0x20,(byte)0xFE});
        rejects("handler hang",()->handler(patched,1,true,3,0,true));
        patched.writeMemory(g(0x7F4FDB),new byte[]{(byte)0xEC,(byte)0x84,0x20,(byte)0xFE});
        rejects("poisoned scratch read",()->handler(patched,1,true,3,0,true));
        original.dispose(); patched.dispose();
        println("PASS: "+count+" full handler cases, valid/invalid payloads, both formats/states, poisoned scratch, ordered effects; hang/read negative controls rejected");
    }
    // 0x184 is CAN1's RXFLG: commands arrive there now, not on CAN4's 0x284.
    final int[] cmdAllowed={0,0x184,0x2250,0x2251,0x2254,0x2255,0x2350};
    // A frame the dispatcher declines is handed on to the stock register
    // decoder, exactly as any non-command frame is. It fills the requester and
    // window bookkeeping before its range check rejects the frame, and never
    // sets busy (26DF) or sends a reply. Reached only while the receiver is
    // idle, because the declined return carries the stock busy test's flags.
    final int[] rejectAllowed={0x184,0x26DB,0x26DC,0x26E2,0x26E3,0x26E4,0x26E5};
    final int[] tickAllowed={0,0x2250,0x2251,0x2254,0x2255,0x2256,0x24E4,0x232C,0x2350};
    void traced(Checked action,int... allowed) throws Exception {
        Trace trace=new Trace(emu,reg("SP")); trace.heaterPolicy(allowed);
        try {action.run();} finally {trace.dispose();}
    }
    void writeMatrix() throws Exception {
        int cases=0;
        for(int stock=0;stock<2;stock++) for(int flag=0;flag<2;flag++) for(int lease:new int[]{0,1,256,65535})
            for(int temp:new int[]{-400,390,391,400,401,32767}) {
                init(); byteAt(0x2350,0xA0|stock); byteAt(0x2256,flag); byteAt(0x24E4,30); word(0x233E,temp);
                traced(()->command(lease),cmdAllowed);
                check(word(0x2254)==lease,"valid command fixture published");
                traced(()->tick(),tickAllowed);
                traced(()->command(0),cmdAllowed);
                traced(()->output(),0,0x2350,0x2256,0x24E4);
                emu.dispose(); cases++;
            }
        for(int dlc=0;dlc<16;dlc++) if(dlc!=8) {
            init(); command(55); final int length=dlc;
            traced(()->can1Command(0xAA55,length,false,65535),rejectAllowed);
            check(word(0x2254)==55,"invalid DLC unchanged"); emu.dispose(); cases++;
        }
        for(boolean rtr:new boolean[]{false,true}) {
            init(); command(55); final boolean remote=rtr;
            traced(()->can1Command(remote?0xAA55:0,8,remote,65535),rejectAllowed);
            check(word(0x2254)==55,"bad magic/RTR unchanged"); emu.dispose(); cases++;
        }
        // The CAN4 0x999 case is inert: a frame that used to be a command must
        // now change nothing at all outside the receiver's own acknowledgement.
        for(int lease:new int[]{0,300,65535}) {
            init(); command(55); frame(0x999,true,8,false,0xAA55,lease);
            traced(()->receive(),0x284); check(word(0x2254)==55 && word(0x2250)==0xAA55,
                "CAN4 0x999 is inert"); emu.dispose(); cases++;
        }
        // Mutations execute actual stores: address-only or final snapshots cannot pass these.
        byte[][] bad={ {(byte)0xC6,0,(byte)0x7B,0x30,0}, // same-value write outside policy
            {0x4C,1,1,0x4D,1,1}, // other MMIO toggle/restore
            {0x4C,0,2,0x4D,0,2}, // transient other relay pin
            {(byte)0xC6,1,(byte)0x7B,0x22,0x3F} }; // outside the live stack
        for(byte[] code:bad) {
            init(); emu.writeMemory(g(0x7F6000),code); emu.writeRegister("PC",0x7F6000);
            rejects("forbidden executed store",()->traced(()->{step();step();step();},0)); emu.dispose();
        }
        println("PASS: per-write policies on "+cases+" scenarios plus same-value/MMIO/transient-pin/stack-corruption negative controls");
        // Exhaustive bit preservation for every initial PORTA and heater-object byte.
        init(); int combinations=0;
        for(int port=0;port<256;port++) for(int obj=0;obj<256;obj++) {
            emu.writeRegister("SP",0x2200); emu.writeRegister("PPAGE",0xFE);
            emu.writeMemory(g(0),new byte[]{(byte)port}); byteAt(0x2350,obj);
            boolean manual=(port&1)!=0; int inhibit=(port>>>1)&1, state=(port&4)==0?3:4;
            word(0x2250,manual?0xAA55:0); word(0x2254,manual?3:0); byteAt(0x2256,inhibit); word(0x233E,399); word(0x2BEC,state);
            traced(()->output(),0,0x2350,0x2256,0x24E4);
            boolean on=inhibit==0 && ((obj&1)!=0 || (manual&&state==3));
            check(read(0)==((port&~8)|(on?8:0)),"PORTA combination");
            check(byteAt(0x2350)==((obj&~4)|(on?4:0)),"object combination"); combinations++;
        }
        emu.dispose(); println("PASS: "+combinations+" exhaustive initial PORTA/object combinations with per-write checking");
    }
    void sequences() throws Exception {
        init(); command(256); tick(); check(word(0x2254)==255,"borrow");
        command(2); tick(); command(65535); tick(); check(word(0x2254)==65534,"renew before expiry");
        command(1); tick(); command(7); check(word(0x2254)==7,"renew after expiry");
        byteAt(0x2256,1); word(0x233E,401); byteAt(0x24E4,30);
        for(int i=0;i<200;i++) {command(3);tick();relay(false,"renewal cannot defeat inhibit");}
        word(0x233E,390); tick(); relay(true,"cooling releases inhibit");
        command(0); tick(); relay(false,"cancel remains cancelled"); emu.dispose();
        for(int state=0;state<=5;state++) {
            init(); command(2); word(0x2BEC,state); tick();
            relay(state==3,"transition gates manual output"); tick();
            check(word(0x2254)==0 && word(0x2250)==0,"expiry in all states");
            word(0x2BEC,3); output(); relay(false,"return to running cannot resurrect lease"); emu.dispose();
        }
        // Actual stock producer, rather than directly injecting only the derived temperature.
        int[][] samples={{250,250,250,250},{450,450,450,450},{250,250,250,500},{-400,250,250,250}};
        int[] expected={250,450,125,-75};
        for(int i=0;i<samples.length;i++) {
            init(); for(int j=0;j<4;j++) word(0x25A0+2*j,samples[i][j]);
            farEntry(0x86CE); runTo(0x4010,5000);
            println("TEMPERATURE producer "+Arrays.toString(samples[i])+" -> "+(short)word(0x233E));
            check((short)word(0x233E)==expected[i],"stock temperature producer case "+i+" actual="+(short)word(0x233E)+" min="+(short)word(0x233C)+" max="+(short)word(0x233A));
            command(300); for(int t=0;t<40;t++) tick();
            int minimum=Arrays.stream(samples[i]).min().getAsInt();
            relay(expected[i]<=400 && minimum<100,"actual producer drives recovery cutoff and stock inhibit");
            println("TEMPERATURE after 40 ticks heater="+((read(0)&8)!=0)); emu.dispose();
        }
        // Real copydown, starting with an armed lease in dirty RAM, before main starts.
        init(); word(0x2250,0xAA55); word(0x2254,1234); emu.writeRegister("PC",0x7F4000);
        runTo(0x400B,50000);
        check(word(0x2250)==0x028A && word(0x2254)==0x3232,"startup copydown restores exact stock initializers and disarms retained lease");
        println("STARTUP actual copydown magic="+Integer.toHexString(word(0x2250))+" count="+word(0x2254)); emu.dispose();
        println("PASS: renewal/inhibit/state-transition sequences and stock temperature producer/startup copydown");
    }
    void interruptTick() throws Exception {
        String[] names={"PC","SP","D","IX","IY","CCR","CCRH","PPAGE","RPAGE","EPAGE"};
        long[] saved=new long[names.length]; for(int i=0;i<names.length;i++) saved[i]=emu.readRegister(names[i]).longValue();
        emu.writeRegister("SP",reg("SP")-16); tick();
        check(reg("SP")==saved[1]-16,"modeled interrupt helper stack");
        for(int i=0;i<names.length;i++) emu.writeRegister(names[i],saved[i]);
    }
    void parentInterleavings() throws Exception {
        int cases=0;
        for(int event=0;event<3;event++) for(int inject=0;inject<200;inject++) {
            init(); command(1); byteAt(0x234C,0); byteAt(0x234D,4); byteAt(0x234E,4); byteAt(0x234F,0);
            farEntry(0x8D07); int n=0; boolean pending=false,delivered=false;
            while((pc()&65535)!=0x4010 && n<500) {
                if(n==inject) pending=true;
                if(pending&&(reg("CCR")&16)==0) {
                    if(event==0) command(0); else if(event==1) command(77); else interruptTick();
                    pending=false; delivered=true;
                }
                step();n++;
            }
            check(n<500,"parent writer completion");
            if(inject>=n) {emu.dispose();break;}
            if(pending) {check((reg("CCR")&16)==0,"pending serviced"); if(event==0)command(0);else if(event==1)command(77);else interruptTick();delivered=true;}
            check(delivered,"event delivered");
            check(word(0x2254)==(event==1?77:0),"event ordering lease"); relay(event==1,"full parent writer interleaving");
            emu.dispose();cases++;
        }
        println("PASS: "+cases+" OFF/renew/PIT-expiry instruction-boundary cases across complete parent PORTA writer (modeled interrupt restoration)");
    }
    void otherBus() throws Exception {
        // CAN1 now carries the same two commands as an RV-C proprietary DGN
        // (18EF7000 | source address). Everything else on that controller must
        // still leave the manual lease, the heater output and the register
        // request state exactly as it found them.
        int count=0,commands=0;
        for(int id:new int[]{0x999,0x18EF7000,0x18EF7001,0x08F62DE2,0x123})
            for(boolean ext:new boolean[]{false,true}) for(int payload:new int[]{0,1,0xAA})
                for(int dlc:new int[]{8,7}) for(boolean rtr:new boolean[]{false,true}) {
                if(!ext && id>0x7FF) continue;
                init(); command(300); int port=read(0),obj=byteAt(0x2350);
                word(0x2622,0x0180); byteAt(0x26DF,0); word(0x26E2,0x5555);
                emu.writeMemory(g(0x1A0),encoded(id,ext,rtr));
                emu.writeMemory(g(0x1A4),new byte[]{(byte)payload,0x55,0,0,0,0,0,0});
                emu.writeMemory(g(0x1AC),new byte[]{(byte)dlc});
                emu.writeRegister("PC",0x7F4BDA); emu.writeRegister("CCR",16);
                runTo(0x4D41,20000);
                boolean addressed=ext && (id&0x1FFFFF00)==0x18EF7000;
                boolean command=addressed && payload==0xAA && dlc==8 && !rtr;
                check(reg("SP")==0x2200-18,"other CAN software frame");
                check(read(0x184)==255,"other CAN acknowledged");
                if(command) {
                    // AA 55 00 00 00 00 00 00 is a heater command with a zero lease.
                    check(word(0x2250)==0 && word(0x2254)==0,"CAN1 heater command cancels the lease");
                    check((read(0)&8)==0 && (byteAt(0x2350)&4)==0,"CAN1 heater command releases PA3");
                    commands++;
                } else {
                    check(word(0x2250)==0xAA55 && word(0x2254)==300,"other CAN cannot alter manual lease");
                    check(read(0)==port && byteAt(0x2350)==obj,"other CAN preserves heater output");
                }
                check(byteAt(0x26DF)==0,"no frame here may start a register request");
                check((word(0x26E2)==0x5555)==(!addressed||command),"command path skips the register decode");
                emu.dispose();count++;
            }
        println("PASS: "+count+" second-controller receive cases, "+commands
            +" accepted as CAN1 proprietary-DGN commands and the rest inert; physical bus mapping and timing not modeled");
    }
    @Override public void run() throws Exception {
        loadImage(); instructionModels(); handlers(); writeMatrix(); sequences(); parentInterleavings(); otherBus();
        println("STRENGTHENED PRE-FLASH SOFTWARE ACCEPTANCE PASSED");
    }
}
