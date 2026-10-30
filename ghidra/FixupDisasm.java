//Iteratively disassemble call targets left as stubs, recover CodeWarrior switch tables (JSR 0x4a70), split merged functions
//@category Setup
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.data.*;
import java.util.*;

public class FixupDisasm extends GhidraScript {
    Address g(long a) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a); }
    long mapLocal(long jsrAddr, int local) {
        if (local >= 0x8000 && local < 0xC000) return (jsrAddr & ~0x3FFFL) + (local - 0x8000);
        return 0x7F0000L + local;
    }
    @Override
    public void run() throws Exception {
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();
        Memory mem = currentProgram.getMemory();
        SymbolTable st = currentProgram.getSymbolTable();
        for (int pass = 0; pass < 15; pass++) {
            int fixed = 0;
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                Address e = f.getEntryPoint();
                if (listing.getInstructionAt(e) == null && listing.getDefinedDataAt(e) == null) {
                    if (disassemble(e)) fixed++;
                }
            }
            AddressIterator it = rm.getReferenceDestinationIterator(mem, true);
            List<Address> todo = new ArrayList<>();
            while (it.hasNext()) {
                Address d = it.next();
                if (d.getOffset() < 0x400000) continue;
                boolean isFlow = false;
                for (Reference r : rm.getReferencesTo(d)) {
                    if (r.getReferenceType().isCall() || r.getReferenceType().isJump()) { isFlow = true; break; }
                }
                if (isFlow && listing.getInstructionAt(d) == null && listing.getDefinedDataAt(d) == null) todo.add(d);
            }
            for (Address d : todo) {
                if (listing.getInstructionAt(d) == null && disassemble(d)) {
                    fixed++;
                    if (getFunctionAt(d) == null) createFunction(d, null);
                }
            }
            // switch tables: JSR 0x4a70 followed by [count][default][case0..]
            InstructionIterator ii = listing.getInstructions(true);
            List<Instruction> jsrs = new ArrayList<>();
            while (ii.hasNext()) {
                Instruction ins = ii.next();
                if (!ins.getMnemonicString().equals("JSR")) continue;
                Reference[] refs = ins.getReferencesFrom();
                for (Reference r : refs) if (r.getToAddress().getOffset() == 0x7f4a70L) jsrs.add(ins);
            }
            for (Instruction ins : jsrs) {
                Address tbl = ins.getAddress().add(ins.getLength());
                if (listing.getDefinedDataAt(tbl) != null) continue; // already handled
                int n = mem.getShort(tbl) & 0xFFFF;
                if (n > 64) continue;
                clearListing(tbl, tbl.add(4 + 2 * n - 1));
                createData(tbl, new WordDataType());
                st.createLabel(tbl, String.format("switch_%s_count", ins.getAddress()), SourceType.ANALYSIS);
                for (int k = 0; k <= n; k++) {
                    Address slot = tbl.add(2 + 2 * k);
                    int local = mem.getShort(slot) & 0xFFFF;
                    long tgt = mapLocal(ins.getAddress().getOffset(), local);
                    createData(slot, new WordDataType());
                    Address ta = g(tgt);
                    rm.addMemoryReference(slot, ta, RefType.DATA, SourceType.ANALYSIS, 0);
                    rm.addMemoryReference(ins.getAddress(), ta, RefType.COMPUTED_JUMP, SourceType.ANALYSIS, 0);
                    if (listing.getInstructionAt(ta) == null) { disassemble(ta); fixed++; }
                    String nm = k == 0 ? String.format("sw_%s_default", ins.getAddress()) : String.format("sw_%s_case%d", ins.getAddress(), k - 1);
                    if (getFunctionAt(ta) == null) createFunction(ta, nm);
                    else if (getFunctionAt(ta).getName().startsWith("FUN_")) getFunctionAt(ta).setName(nm, SourceType.ANALYSIS);
                }
                println("switch at " + ins.getAddress() + " with " + n + " cases");
            }
            // split merged functions: call targets inside another function's body
            List<Address> splits = new ArrayList<>();
            it = rm.getReferenceDestinationIterator(mem, true);
            while (it.hasNext()) {
                Address d = it.next();
                if (d.getOffset() < 0x400000) continue;
                boolean isCall = false;
                for (Reference r : rm.getReferencesTo(d)) if (r.getReferenceType().isCall()) { isCall = true; break; }
                if (!isCall) continue;
                Function fc = getFunctionContaining(d);
                if (fc != null && !fc.getEntryPoint().equals(d)) splits.add(d);
            }
            for (Address d : splits) {
                Function fc = getFunctionContaining(d);
                if (fc == null || fc.getEntryPoint().equals(d)) continue;
                Address pe = fc.getEntryPoint(); String pn = fc.getName();
                removeFunction(fc);
                createFunction(d, null);
                Function np = createFunction(pe, pn.startsWith("FUN_") ? null : pn);
                println("split " + d + " out of " + pe);
                fixed++;
            }
            println("pass " + pass + ": fixed " + fixed);
            if (fixed == 0) break;
            analyzeAll(currentProgram);
        }
        int n = 0;
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) n++;
        println("functions now: " + n);
    }
}
