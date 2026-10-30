//Apply the kit's markup (markup/*.tsv): data types, function names, labels, plate comments, struct applications
//@category Setup
//
// Argument: the markup directory. Run after SetupHCS12.java and FixupDisasm.java.
// Prints "MARKUP functions=N labels=N structs=N uses=N errors=N"; every error is listed
// above that line and reported through Msg.error.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.Msg;
import java.nio.file.*;
import java.util.*;

public class ApplyMarkup extends GhidraScript {
    static final CategoryPath CATEGORY = new CategoryPath("/renogy_bms");
    // RAM local 0x2000-0x3FFF appears at global 0x0FE000-0x0FFFFF.
    static final long RAM_START = 0x0FE000L, RAM_SIZE = 0x2000L;
    final List<String> errors = new ArrayList<>();
    final Map<String, Structure> structs = new LinkedHashMap<>();

    Address g(String hex) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(Long.parseLong(hex, 16)); }

    List<String[]> read(Path dir, String name, int columns) throws Exception {
        List<String[]> rows = new ArrayList<>();
        boolean header = true;
        for (String line : Files.readAllLines(dir.resolve(name))) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (header) { header = false; continue; }
            String[] cells = line.split("\t", -1);
            if (cells.length != columns) { errors.add(name + ": wrong column count: " + line); continue; }
            rows.add(cells);
        }
        return rows;
    }

    void error(String message) { errors.add(message); }

    DataType type(String name) {
        int bracket = name.indexOf('[');
        if (bracket > 0) {
            int n = Integer.parseInt(name.substring(bracket + 1, name.length() - 1));
            DataType element = type(name.substring(0, bracket));
            return new ArrayDataType(element, n, element.getLength());
        }
        switch (name) {
            case "byte": return ByteDataType.dataType;
            case "sbyte": return SignedByteDataType.dataType;
            case "word": return WordDataType.dataType;
            case "sword": return SignedWordDataType.dataType;
            case "dword": return DWordDataType.dataType;
            case "char": return CharDataType.dataType;
        }
        if (structs.containsKey(name)) return structs.get(name);
        throw new IllegalArgumentException("unknown type " + name);
    }

    void ensureRam() throws Exception {
        Memory mem = currentProgram.getMemory();
        Address start = g(Long.toHexString(RAM_START));
        if (mem.contains(start)) return;
        MemoryBlock ram = mem.createUninitializedBlock("RAM", start, RAM_SIZE, false);
        ram.setRead(true); ram.setWrite(true); ram.setExecute(false); ram.setVolatile(false);
    }

    int applyStructs(Path dir) throws Exception {
        Map<String, List<String[]>> fields = new LinkedHashMap<>();
        for (String[] r : read(dir, "structs.tsv", 5)) fields.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r);
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        for (Map.Entry<String, List<String[]>> e : fields.entrySet()) {
            int size = 0;
            for (String[] f : e.getValue()) size = Math.max(size, Integer.parseInt(f[1]) + typeLength(f[2]));
            StructureDataType s = new StructureDataType(CATEGORY, e.getKey(), size, dtm);
            for (String[] f : e.getValue()) {
                DataType t = type(f[2]);
                s.replaceAtOffset(Integer.parseInt(f[1]), t, t.getLength(), f[3], f[4].isEmpty() ? null : f[4]);
            }
            structs.put(e.getKey(), (Structure) dtm.addDataType(s, DataTypeConflictHandler.REPLACE_HANDLER));
        }
        return structs.size();
    }

    int typeLength(String name) { return type(name).getLength(); }

    int applyFunctions(Path dir) throws Exception {
        int n = 0;
        Listing listing = currentProgram.getListing();
        for (String[] r : read(dir, "functions.tsv", 4)) {
            Address a = g(r[0]);
            if (!currentProgram.getMemory().contains(a)) { error("function " + r[1] + " at " + r[0] + ": address outside memory"); continue; }
            Function f = getFunctionAt(a);
            if (f == null) {
                if (listing.getInstructionAt(a) == null) disassemble(a);
                f = createFunction(a, r[1]);
            }
            if (f == null) { error("function " + r[1] + " at " + r[0] + ": could not create a function"); continue; }
            if (f.getSymbol().getSource() == SourceType.USER_DEFINED && !f.getName().equals(r[1])
                    && !f.getName().startsWith("ISR_vec") && !f.getName().equals("_Startup")) {
                error("function at " + r[0] + ": already named " + f.getName() + ", markup says " + r[1]);
                continue;
            }
            f.setName(r[1], SourceType.USER_DEFINED);
            String plate = comment(r[2], r[3]);
            if (plate != null) f.setComment(plate);
            n++;
        }
        return n;
    }

    String comment(String summary, String details) {
        if (summary.isEmpty()) return null;
        return details.isEmpty() ? summary : summary + "\n\n" + details.replace("\\n", "\n");
    }

    int applyLabels(Path dir) throws Exception {
        int n = 0;
        SymbolTable st = currentProgram.getSymbolTable();
        for (String[] r : read(dir, "labels.tsv", 4)) {
            Address a = g(r[0]);
            Symbol s = st.createLabel(a, r[1], SourceType.USER_DEFINED);
            s.setPrimary();
            if (!r[3].isEmpty() && currentProgram.getMemory().contains(a))
                currentProgram.getListing().setComment(a, CodeUnit.PLATE_COMMENT, r[3]);
            n++;
        }
        return n;
    }

    int applyUses(Path dir) throws Exception {
        int n = 0;
        for (String[] r : read(dir, "struct_uses.tsv", 3)) {
            Address a = g(r[0]);
            Structure s = structs.get(r[1]);
            if (s == null) { error("struct use at " + r[0] + ": unknown struct " + r[1]); continue; }
            int count = Integer.parseInt(r[2]);
            DataType t = count == 1 ? s : new ArrayDataType(s, count, s.getLength());
            if (!currentProgram.getMemory().contains(a.add(t.getLength() - 1))) { error("struct use at " + r[0] + ": outside memory"); continue; }
            clearListing(a, a.add(t.getLength() - 1));
            createData(a, t);
            n++;
        }
        return n;
    }

    @Override
    public void run() throws Exception {
        Path dir = Paths.get(getScriptArgs()[0]);
        ensureRam();
        int nStructs = applyStructs(dir);
        int nFunctions = applyFunctions(dir);
        int nLabels = applyLabels(dir);
        int nUses = applyUses(dir);
        for (String e : errors) println("MARKUP ERROR " + e);
        println(String.format("MARKUP functions=%d labels=%d structs=%d uses=%d errors=%d",
                nFunctions, nLabels, nStructs, nUses, errors.size()));
        if (!errors.isEmpty()) Msg.error(this, errors.size() + " markup errors; see the MARKUP ERROR lines");
    }
}
