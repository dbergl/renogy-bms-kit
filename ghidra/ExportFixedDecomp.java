//Export decompiled C with the HCS12 decompiler fixes. Run it under the Ghidra copy built by
//tools/fix_ghidra.py (BRSET, BGE/LBGE, CPE* semantics); it also replaces the three runtime
//helpers that return their result in the condition codes (rt_fcmp, rt_lcmp, the double compare
//at 7F4370) with p-code call fixups, so the C shows real comparisons. The project is opened
//read-only: nothing is saved. Never commit the output.
//Args: <outDir> [function names...]   (no names = every function)
//@category Export
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.database.SpecExtension;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.*;
import java.util.*;

public class ExportFixedDecomp extends GhidraScript {
    // A call fixup replaces only the CALL op: JSR has already pushed its 2-byte return
    // address, so arguments start at SP+2 and the fixup pops the return address (the RTS).
    // CCR bits: N=3, Z=2, V=1, C=0. Signed branches read N^V, unsigned read C/Z.
    static final String[][] FIXUPS = {
        // rt_fcmp 7F467F: flags of float (X:D) - float pushed at SP+2 (caller pops 4).
        {"hcs12fix_rt_fcmp", "0x7f467f",
         "local pa:3 = segment(physPage, SP + 2);\n" +
         "local lhs:4 = (zext(IX) << 16) | zext(D);\n" +
         "local rhs:4 = *:4 pa;\n" +
         "local lt:1 = lhs f< rhs;\n" +
         "local eq:1 = lhs f== rhs;\n" +
         "CCR = (CCR & 0xf0) | (lt << 3) | (eq << 2) | lt;\n" +
         "SP = SP + 2;\n"},
        // rt_lcmp 7F4920: flags of 32-bit (X:D) - long pushed at SP+2; callee pops 4 + return address.
        {"hcs12fix_rt_lcmp", "0x7f4920",
         "local pa:3 = segment(physPage, SP + 2);\n" +
         "local lhs:4 = (zext(IX) << 16) | zext(D);\n" +
         "local rhs:4 = *:4 pa;\n" +
         "local slt:1 = lhs s< rhs;\n" +
         "local ult:1 = lhs < rhs;\n" +
         "local eq:1 = lhs == rhs;\n" +
         "CCR = (CCR & 0xf0) | (slt << 3) | (eq << 2) | ult;\n" +
         "SP = SP + 6;\n"},
        // FUN_7f4370: signed compare of two doubles, (SP+2) - (SP+10); caller pops 16.
        {"hcs12fix_dcmp", "0x7f4370",
         "local pa:3 = segment(physPage, SP + 2);\n" +
         "local pb:3 = segment(physPage, SP + 10);\n" +
         "local lhs:8 = *:8 pa;\n" +
         "local rhs:8 = *:8 pb;\n" +
         "local lt:1 = lhs f< rhs;\n" +
         "local eq:1 = lhs f== rhs;\n" +
         "CCR = (CCR & 0xf0) | (lt << 3) | (eq << 2) | lt;\n" +
         "SP = SP + 2;\n"},
    };

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String outDir = args.length > 0 ? args[0] : "/tmp";
        Set<String> only = new HashSet<>(Arrays.asList(args).subList(Math.min(1, args.length), args.length));
        SpecExtension ext = new SpecExtension(currentProgram);
        for (String[] f : FIXUPS) {
            String xml = "<callfixup name=\"" + f[0] + "\"><pcode><body><![CDATA[\n" + f[2] +
                         "]]></body></pcode></callfixup>";
            ext.addReplaceCompilerSpecExtension(xml, monitor);
            Function fn = getFunctionAt(toAddr(f[1]));
            if (fn == null) throw new AssertionError("no function at " + f[1]);
            fn.setCallFixup(f[0]);
            println("call fixup " + f[0] + " on " + fn.getName());
        }
        DecompInterface ifc = new DecompInterface();
        ifc.setOptions(new DecompileOptions());
        if (!ifc.openProgram(currentProgram)) throw new AssertionError(ifc.getLastMessage());
        new File(outDir).mkdirs();
        int n = 0, failed = 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir, "decompiled.c")))) {
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                if (monitor.isCancelled()) break;
                if (!only.isEmpty() && !only.contains(f.getName())) continue;
                DecompileResults res = ifc.decompileFunction(f, 60, new ConsoleTaskMonitor());
                pw.println("// ===== " + f.getName() + " @ " + f.getEntryPoint() + " =====");
                if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                    pw.println(res.getDecompiledFunction().getC());
                } else {
                    failed++;
                    pw.println("// DECOMPILE FAILED: " + (res == null ? "null" : res.getErrorMessage()));
                }
                n++;
            }
        }
        ifc.dispose();
        println("Exported " + n + " functions, " + failed + " failed");
    }
}
