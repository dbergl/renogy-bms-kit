// Strict test support. Firmware and installed Ghidra definitions are never edited.
//@category Test
import ghidra.app.emulator.*;
import ghidra.program.model.address.*;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.memstate.MemoryState;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import java.nio.file.*;
import java.util.*;

public class VerifiedHeaterHarness extends DecoderHeaterTest {
    int beforeSP, instructionCount, minimumSP;
    long instructionPC;
    Set<Integer> visited = new HashSet<>();

    @Override void setup(long entry,int page) throws Exception {
        super.setup(entry,page);
        emu.registerCallOtherCallback("LoadStack",new BreakCallBack() {
            @Override public boolean pcodeCallback(PcodeOpRaw op) {
                MemoryState state=emulate.getMemoryState();
                state.setValue(op.getOutput(),state.getValue(op.getInput(1))&65535);
                return true;
            }
        });
        // Explicit test fixtures, not a simulation of power-on peripherals.
        emu.writeMemory(g(0),new byte[0x800]);
        emu.writeMemory(ram(0x2000),new byte[0x2000]);
        emu.writeRegister("PPAGE",page);
        emu.writeRegister("EPAGE",0xFE);
        emu.writeRegister("RPAGE",0xFD);
        emu.writeRegister("CCRH",0);
    }
    long global(int local) {
        local&=65535;
        if(local<0x800) return local;
        if(local>=0x2000 && local<0x4000) return RAM+local;
        if(local>=0x8000 && local<0xC000) return 0x400000L+(reg("PPAGE")&255)*0x4000L+local-0x8000;
        if(local>=0x4000) return 0x7F0000L|local;
        throw new AssertionError("unmodeled local address "+Integer.toHexString(local));
    }
    int read(long address) { return emu.readMemory(g(address),1)[0]&255; }
    // BRSET emulation is wrong in the supported Ghidra build. Derive its correct
    // next PC from pre-instruction state, execute normal reads, then override PC.
    // Only explicitly decoded addressing forms are accepted; unknown forms fail.
    Long correctedBranch(long address) {
        int op=read(address),len=0,mask=0,offset=0,local=0;
        // Ghidra 12.0.4 also reverses BGE/LBGE. NXP HCS12COREUG,
        // BGE: branch iff N xor V == 0. Firmware bytes stay untouched.
        if(op==0x2C || (op==0x18 && read(address+1)==0x2C)) {
            len=op==0x2C?2:4;
            offset=op==0x2C?(byte)read(address+1):(short)((read(address+2)<<8)|read(address+3));
            int ccr=reg("CCR");
            return address+len+((((ccr>>>3)^(ccr>>>1))&1)==0?offset:0);
        }
        if(op==0x1E) {local=(read(address+1)<<8)|read(address+2); mask=read(address+3); offset=(byte)read(address+4); len=5;}
        else if(op==0x4E) {local=read(address+1); mask=read(address+2); offset=(byte)read(address+3); len=4;}
        else if(op==0x0E) {
            int pb=read(address+1),base,disp;
            if((pb&0x20)==0) {
                int selector=pb>>>6;
                base=selector==0?reg("IX"):selector==1?reg("IY"):selector==2?reg("SP"):(int)(address+2)&65535;
                disp=(pb&31); if(disp>=16) disp-=32;
                len=4; mask=read(address+2); offset=(byte)read(address+3);
            } else if((pb&0xE7)==0xE2) {
                int selector=(pb>>>3)&3;
                base=selector==0?reg("IX"):selector==1?reg("IY"):selector==2?reg("SP"):(int)(address+4)&65535;
                disp=(short)((read(address+2)<<8)|read(address+3));
                len=6; mask=read(address+4); offset=(byte)read(address+5);
            } else throw new AssertionError("untested BRSET indexed form "+Integer.toHexString(pb));
            local=(base+disp)&65535;
        } else return null;
        int value=read(global(local));
        return address+len+(((value&mask)==mask)?offset:0);
    }
    @Override void step() throws Exception {
        long address=pc();
        // Ghidra's 16-bit PC can contain the low half of a translated global
        // destination (e.g. FC:95AE becomes 15AE); match the base harness.
        if(address<0x10000) address=address>=0x8000 && address<0xC000
            ? global((int)address) : 0x7F0000L|address;
        instructionPC=address; beforeSP=reg("SP");
        visited.add((int)(address&65535));
        Long next=correctedBranch(address);
        super.step();
        if(next!=null) emu.writeRegister("PC",next);
        instructionCount++; minimumSP=Math.min(minimumSP,reg("SP"));
    }
    void counters() { instructionCount=0; minimumSP=reg("SP"); visited.clear(); }
    void runTo(int stop,int bound) throws Exception {
        for(int i=0;i<bound;i++) { if((pc()&65535)==stop) return; step(); }
        check((pc()&65535)==stop,"instruction bound exceeded at "+Long.toHexString(pc())+" expected "+Integer.toHexString(stop));
    }
    void farEntry(int entry) throws Exception {
        int sp=reg("SP"),page=reg("PPAGE");
        emu.writeRegister("SP",sp-3);
        emu.writeMemory(ram(sp-3),new byte[]{(byte)page,0x40,0x10});
        emu.writeRegister("PPAGE",0xFE); emu.writeRegister("PC",global(entry));
    }
    void restoreStock(EmulatorHelper target,long start,long end) throws Exception {
        int count=0;
        for(String line:Files.readAllLines(Path.of(getScriptArgs()[1]))) {
            if(!line.startsWith("S3")) continue;
            long at=Long.parseLong(line.substring(4,12),16);
            byte[] data=HexFormat.of().parseHex(line.substring(12,line.length()-2));
            for(int i=0;i<data.length;i++) if(at+i>=start && at+i<end) {
                target.writeMemory(g(at+i),new byte[]{data[i]}); count++;
            }
        }
        check(count==end-start,"complete stock range restored");
    }
    void pair() throws Exception {
        setup(0x7F4D47L,0xFE); patched=emu;
        setup(0x7F4D47L,0xFE); original=emu;
        restoreStock(original,0x7F4D78L,0x7F4E39L);
    }

    class Trace extends MemoryAccessFilter {
        final EmulatorHelper owner;
        final int top;
        final List<String> effects=new ArrayList<>();
        final Set<Long> poison=new HashSet<>();
        Set<Long> allowed=null;
        boolean enforce=false;
        int initialPort,initialObject;
        Trace(EmulatorHelper h,int stackTop) {
            owner=h; top=stackTop; setFilterOnExecutionOnly(true);
            h.getEmulator().addMemoryAccessFilter(this);
        }
        int sp() { return owner.readRegister("SP").intValue(); }
        boolean stack(long address) {
            return address>=RAM+Math.min(beforeSP,sp()) && address<RAM+top;
        }
        @Override protected void processRead(AddressSpace space,long offset,int size,byte[] data) {
            if(!space.isMemorySpace()) return;
            for(int i=0;i<size;i++) if(poison.contains(offset+i))
                throw new AssertionError("read poisoned scratch before overwrite at "+Long.toHexString(offset+i)+" pc="+Long.toHexString(instructionPC));
        }
        @Override protected void processWrite(AddressSpace space,long offset,int size,byte[] data) {
            if(!space.isMemorySpace()) return;
            for(int i=0;i<size;i++) {
                long address=offset+i; int value=data[i]&255;
                poison.remove(address);
                if(stack(address)) continue;
                effects.add(String.format("%06X:%02X",address,value));
                if(enforce) {
                    // CALL/RTC update the memory-mapped PPAGE register. Their
                    // restoration is independently asserted by each call fixture.
                    int opcode=owner.readMemory(g(instructionPC),1)[0]&255;
                    if(address==0x30 && (opcode==0x4A || opcode==0x4B || opcode==0x0A)) continue;
                    if(!allowed.contains(address)) throw new AssertionError("forbidden write "+Long.toHexString(address)+" pc="+Long.toHexString(instructionPC));
                    if(address==0 && (value&~8)!=(initialPort&~8)) throw new AssertionError("transient unrelated PORTA bit change");
                    if(address==RAM+0x2350 && (value&~4)!=(initialObject&~4)) throw new AssertionError("unrelated relay object bits changed");
                    if((address==0x284||address==0x184) && value!=255) throw new AssertionError("unexpected CAN acknowledgement");
                }
            }
        }
        void heaterPolicy(int... local) throws Exception {
            allowed=new HashSet<>(); for(int address:local) allowed.add(global(address));
            initialPort=owner.readMemory(g(0),1)[0]&255;
            initialObject=owner.readMemory(ram(0x2350),1)[0]&255;
            enforce=true;
        }
    }
    interface Checked { void run() throws Exception; }
    void rejects(String description,Checked action) throws Exception {
        boolean failed=false;
        println("NEGATIVE CONTROL BEGIN: "+description);
        try { action.run(); } catch(AssertionError failure) {failed=true;}
        check(failed,"negative control escaped: "+description);
        println("NEGATIVE CONTROL REJECTED: "+description);
    }
    void instructionModels() throws Exception {
        // All values/masks for each BRSET addressing form used by these tests.
        byte[][] forms={ {(byte)0x1E,0x26,(byte)0x80,0,3}, {0x4E,0x40,0,3},
            {0x0E,(byte)0x85,0,3}, {0x0E,(byte)0xE2,0x26,(byte)0x80,0,3} };
        int[] locations={0x2680,0x40,0x2205,0x2680};
        for(int f=0;f<forms.length;f++) for(int mask=0;mask<256;mask++) {
            // Fresh decoder state for each code sequence: changing bytes at the
            // current instruction can otherwise retain Ghidra's cached decode.
            init();
            byte[] code=forms[f].clone(); code[code.length-2]=(byte)mask;
            emu.writeMemory(g(0x7F6000),code);
            for(int value=0;value<256;value++) {
            emu.writeMemory(g(global(locations[f])),new byte[]{(byte)value});
            emu.writeRegister("IX",0); emu.writeRegister("PC",0x7F6000);
            step();
            check((pc()&65535)==0x6000+code.length+((value&mask)==mask?3:0),"BRSET truth table");
            }
            emu.dispose();
        }
        println("PASS: BRSET model truth tables, 262144 cases across direct/extended/indexed forms");
        int branches=0;
        for(boolean wide:new boolean[]{false,true}) for(int displacement:new int[]{-10,10}) {
            init();
            byte[] code=wide?new byte[]{0x18,0x2C,(byte)(displacement>>8),(byte)displacement}:new byte[]{0x2C,(byte)displacement};
            emu.writeMemory(g(0x7F6000),code);
            for(int ccr=0;ccr<256;ccr++) {
            emu.writeRegister("PC",0x7F6000);emu.writeRegister("CCR",ccr);
            step();boolean branch=((ccr&8)!=0)==((ccr&2)!=0);
            check((pc()&65535)==0x6000+code.length+(branch?displacement:0),"BGE/LBGE truth table");
            check(reg("CCR")==ccr,"BGE preserves flags");branches++;
            }
            emu.dispose();
        }
        println("PASS: "+branches+" BGE/LBGE flag/displacement cases");
        for(int value:new int[]{0,1,0x2200,0x2250,0x7FFF,0x8000,0xFFFF}) {
            init();
            emu.writeMemory(g(0x7F6000),new byte[]{(byte)0xCF,(byte)(value>>8),(byte)value});
            emu.writeRegister("PC",0x7F6000);emu.writeRegister("CCR",0xFF);step();
            check(reg("SP")==value,"LDS operand loaded expected="+value+" actual="+reg("SP"));
            check((reg("CCR")&14)==((value==0?4:0)|((value&0x8000)!=0?8:0)),"LDS NZV flags");
            emu.dispose();
        }
        println("PASS: LDS callback boundary operands and flags");
    }
    @Override public void run() throws Exception { throw new IllegalStateException("support class"); }
}
