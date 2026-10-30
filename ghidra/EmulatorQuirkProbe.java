// Probe the raw Ghidra emulator, with none of the harness corrections, for the
// three behaviours the test harness works around. Reports, does not assert:
// run it under each Ghidra version to see which corrections are still needed.
// Args: any global S19 (the harness loader needs one).
//@category Test
import java.util.*;

public class EmulatorQuirkProbe extends EmuHeaterTest {
    long rawStep(long at) throws Exception {
        emu.writeRegister("PC",at);
        check(emu.step(monitor),"emulator step: "+emu.getLastError());
        return pc()&65535;
    }
    @Override public void run() throws Exception {
        loadImage();
        println("Ghidra "+ghidra.framework.Application.getApplicationVersion());
        // BGE: branch iff N xor V == 0 (NXP HCS12COREUG).
        int bgeWrong=0;
        setup(0x7F6000L,0xFE);
        emu.writeMemory(g(0x7F6000L),new byte[]{0x2C,0x10});
        for(int ccr=0;ccr<256;ccr++) {
            emu.writeRegister("CCR",ccr);
            boolean branch=(((ccr>>>3)^(ccr>>>1))&1)==0;
            if(rawStep(0x7F6000L)!=(branch?0x6012:0x6002)) bgeWrong++;
        }
        emu.dispose();
        println("PROBE BGE: "+bgeWrong+" of 256 CCR values branch wrongly");
        // Every other short conditional branch, against the NXP condition table.
        String[] names={"BHI","BLS","BCC","BCS","BNE","BEQ","BVC","BVS","BPL","BMI","BGE","BLT","BGT","BLE"};
        StringBuilder wrongBranches=new StringBuilder();
        for(int op=0x22;op<=0x2F;op++) {
            int wrong=0;
            setup(0x7F6000L,0xFE);
            emu.writeMemory(g(0x7F6000L),new byte[]{(byte)op,0x10});
            for(int ccr=0;ccr<256;ccr++) {
                boolean c=(ccr&1)!=0,v=(ccr&2)!=0,z=(ccr&4)!=0,n=(ccr&8)!=0;
                boolean[] cond={!(c||z),c||z,!c,c,!z,z,!v,v,!n,n,n==v,n!=v,!z&&n==v,z||n!=v};
                emu.writeRegister("CCR",ccr);
                if(rawStep(0x7F6000L)!=(cond[op-0x22]?0x6012:0x6002)) wrong++;
            }
            emu.dispose();
            if(wrong>0) wrongBranches.append(" "+names[op-0x22]+"="+wrong);
        }
        println("PROBE short conditional branches wrong (of 256 CCR each):"+(wrongBranches.length()==0?" none":wrongBranches));
        // BRSET extended: branch iff (M & mask) == mask.
        int brsetWrong=0;
        for(int mask:new int[]{0x01,0x02,0x80,0x81,0xFF,0x00}) {
            setup(0x7F6000L,0xFE);
            emu.writeMemory(g(0x7F6000L),new byte[]{0x1E,0x26,(byte)0x80,(byte)mask,0x10});
            for(int v=0;v<256;v++) {
                byteAt(0x2680,v);
                boolean branch=(v&mask)==mask;
                if(rawStep(0x7F6000L)!=(branch?0x6015:0x6005)) brsetWrong++;
            }
            emu.dispose();
        }
        println("PROBE BRSET extended: "+brsetWrong+" of 1536 mask/value cases branch wrongly");
        // writeMemory buffer aliasing: after writing a buffer, does an emulated
        // store change the caller's array? (It did, for an 8 KB RAM baseline.)
        for(int size:new int[]{0x100,0x1000,0x2000}) {
            setup(0x7F6000L,0xFE);
            byte[] passed=null; int at=0;   // the buffer handed over that covers 0x2396
            for(int off=0;off<0x2000;off+=size) {
                byte[] chunk=new byte[size];
                emu.writeMemory(ram(0x2000+off),chunk);
                if(0x396>=off && 0x396<off+size) { passed=chunk; at=0x396-off; }
            }
            emu.writeMemory(g(0x7F6000L),new byte[]{0x72,0x23,(byte)0x96});   // INC 0x2396
            rawStep(0x7F6000L);
            println("PROBE writeMemory of "+size+"-byte buffers: emulator 0x2396="+byteAt(0x2396)
                +", buffer passed to writeMemory="+(passed[at]&255)+(passed[at]!=0?"  <- buffer aliased":""));
            emu.dispose();
        }
    }
}
