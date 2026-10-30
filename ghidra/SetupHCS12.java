//Pre-analysis setup: PPAGE context per flash page, entry points from vectors
//@category Setup
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import java.math.BigInteger;

public class SetupHCS12 extends GhidraScript {
    Address g(long a) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a); }
    @Override
    public void run() throws Exception {
        ProgramContext ctx = currentProgram.getProgramContext();
        Register ppage = ctx.getRegister("PPAGE");
        println("PPAGE register: " + ppage);
        int[] pages = {0xE0, 0xFC, 0xFE};
        for (int p : pages) {
            long base = 0x400000L + (long)p * 0x4000L;
            try {
                ctx.setValue(ppage, g(base), g(base + 0x3FFF), BigInteger.valueOf(p));
                println(String.format("PPAGE=%02X over %06X-%06X", p, base, base+0x3FFF));
            } catch (Exception e) { println("setValue failed: " + e); }
        }
        // unpaged regions: page FD (0x4000) and FF (0xC000) -> leave default PPAGE (FE)
        Memory mem = currentProgram.getMemory();
        SymbolTable st = currentProgram.getSymbolTable();
        // reset / startup entry
        Address start = g(0x7F4000);
        st.addExternalEntryPoint(start);
        disassemble(start);
        createFunction(start, "_Startup");
        st.createLabel(start, "_Startup", SourceType.USER_DEFINED);
        // application vector table (bootloader-relocated to 0x7F80..0x7FFF)
        String[] names = new String[0x80];
        for (int off = 0x7F10; off < 0x8000; off += 2) {
            Address va = g(0x7F0000L + off);
            if (!mem.contains(va)) continue;
            int v = mem.getShort(va) & 0xFFFF;
            if (v == 0 || v == 0xFFFF || v < 0x4000) continue;
            long target = 0x7F0000L + v;
            Address ta = g(target);
            if (!mem.contains(ta)) continue;
            String nm = String.format("ISR_vec%04X", off + 0x8000);
            st.createLabel(va, String.format("VEC_%04X", off + 0x8000), SourceType.USER_DEFINED);
            st.addExternalEntryPoint(ta);
            disassemble(ta);
            if (getFunctionAt(ta) == null) createFunction(ta, nm);
            println(String.format("vector %04X -> %04X (%06X)", off + 0x8000, v, target));
        }
    }
}
