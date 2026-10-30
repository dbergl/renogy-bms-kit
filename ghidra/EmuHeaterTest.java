// In-place firmware acceptance tests. Pass the global S19 path as script argument.
//@category Test
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.*;
import ghidra.pcode.emulate.BreakCallBack;
import ghidra.pcode.memstate.MemoryState;
import ghidra.pcode.pcoderaw.PcodeOpRaw;
import java.nio.file.*;
import java.util.*;

public class EmuHeaterTest extends GhidraScript {
    Address g(long a) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a); }
    static final long RAM = 0x0FE000L - 0x2000L; // local RAM 0x2xxx -> global
    Address ram(int local) { return g(RAM + local); }
    EmulatorHelper emu;

    void setup(long entry, int ppage) throws Exception {
        emu = new EmulatorHelper(currentProgram);
        // implement the HCS12X "segment" userop: res = base ^ zext(inner)
        emu.registerCallOtherCallback("segment", new BreakCallBack() {
            @Override public boolean pcodeCallback(PcodeOpRaw op) {
                MemoryState ms = emulate.getMemoryState();
                long base = ms.getValue(op.getInput(1));
                long inner = ms.getValue(op.getInput(2)) & 0xFFFF;
                long res;
                if (base != 0) res = base ^ inner;
                else if (inner < 0x0800) res = inner;                                   // registers
                else if (inner < 0x1000) res = 0x0FE000L - 0x1000 + inner;              // EEPROM window (unused here)
                else if (inner < 0x2000) res = 0x0F0000L + (ms.getValue(currentProgram.getRegister("RPAGE")) & 0xFF) * 0x1000L + (inner - 0x1000);
                else if (inner < 0x4000) res = 0x0FE000L + (inner - 0x2000);           // unpaged RAM
                else if (inner < 0x8000) res = 0x7F0000L | inner;                       // unpaged flash page FD
                else if (inner < 0xC000) res = 0x400000L + (ms.getValue(currentProgram.getRegister("PPAGE")) & 0xFF) * 0x4000L + (inner - 0x8000);
                else res = 0x7F0000L | inner;                                           // unpaged flash page FF
                ms.setValue(op.getOutput(), res);
                return true;
            }
        });
        emu.writeRegister("SP", 0x2200);
        emu.writeRegister("CCR", 0);
        emu.writeRegister("EPAGE", 0xFE);
        emu.writeRegister("PPAGE", ppage);
        emu.writeRegister("RPAGE", 0xFD);
        emu.writeRegister("A", 0x12); emu.writeRegister("B", 0x34); emu.writeRegister("IX", 0); emu.writeRegister("IY", 0);
        emu.writeRegister("PC", entry);
    }
    int assertions = 0;
    void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }
    void loadImage() throws Exception {
        int count=0;
        for (String line : Files.readAllLines(Path.of(getScriptArgs()[0]))) {
            if (!line.startsWith("S3")) continue;
            long address=Long.parseLong(line.substring(4,12),16);
            byte[] bytes=HexFormat.of().parseHex(line.substring(12,line.length()-2));
            if (!currentProgram.getMemory().contains(g(address)))
                currentProgram.getMemory().createInitializedBlock("v2_"+Long.toHexString(address), g(address), bytes.length, (byte)0, monitor, false);
            clearListing(g(address),g(address+bytes.length-1));
            currentProgram.getMemory().setBytes(g(address),bytes);
            byte[] actual=new byte[bytes.length];
            currentProgram.getMemory().getBytes(g(address),actual);
            check(Arrays.equals(bytes,actual), "image byte identity");
            count+=bytes.length;
        }
        println("Loaded exact test S19 into read-only project transaction: "+count+" bytes");
        for(long entry:new long[]{0x7F9139L,0x7F4DF8L,0x7F4E27L,0x7F4DC5L,0x7F55BAL}) disassemble(g(entry));
        for(long start:new long[]{0x7F5581L,0x7F4D78L}) {
            disassemble(g(start));
            for(long a=start;a<start+(start==0x7F5581L?77:193);) {
                var ins=currentProgram.getListing().getInstructionAt(g(a));
                if(ins==null) {a++;continue;}
                println(String.format("%06X %s",a,ins)); a+=ins.getLength();
            }
        }
    }
    int byteAt(int a) throws Exception { return emu.readMemory(ram(a),1)[0]&255; }
    void byteAt(int a,int v) throws Exception { emu.writeMemory(ram(a),new byte[]{(byte)v}); }
    int word(int a) throws Exception { return (byteAt(a)<<8)|byteAt(a+1); }
    void word(int a,int v) throws Exception { byteAt(a,v>>8); byteAt(a+1,v); }
    long pc() { return emu.readRegister("PC").longValue(); }
    int reg(String r) { return emu.readRegister(r).intValue(); }
    void step() throws Exception {
        long pc=pc();
        if (pc<0x10000) emu.writeRegister("PC", pc>=0x8000 && pc<0xC000
            ? 0x400000L+(reg("PPAGE")&255)*0x4000L+pc-0x8000 : 0x7F0000L|pc);
        int old=reg("CCR");
        check(emu.step(monitor), "emulator step: "+emu.getLastError());
        // Ghidra 12.0.4 CPEX needs sticky zero for the stock 32-bit ID comparison.
        if ((pc&65535)==0x4922 && (old&4)==0) emu.writeRegister("CCR",reg("CCR")&~4);
    }
    void until(int stop) throws Exception {
        for(int i=0;i<5000;i++) {
            if ((pc()&65535)==stop) return;
            step();
        }
        throw new AssertionError("instruction limit before "+Integer.toHexString(stop));
    }
    void init() throws Exception {
        setup(0x7F9139L,0xFE);
        emu.writeMemory(ram(0x2250),new byte[8]);
        emu.writeMemory(ram(0x232C),new byte[64]);
        byteAt(0x24E4,0);
        word(0x233E,250);
        word(0x233C,0);         // coldest probe below normal recovery
        byteAt(0x2652,5);       // stock charge low-temperature L1, degrees C
        word(0x2BEC,3);
        word(0x2BE6,2);
        word(0x261E,0x0280);
        word(0x2622,0x0180);    // CAN1 register base: the bus the commands arrive on
        byteAt(0x26DF,0);       // receiver idle
        emu.writeMemory(g(0),new byte[]{(byte)0xA5});
    }
    void output() throws Exception {
        int sp=reg("SP"), page=reg("PPAGE");
        emu.writeRegister("SP",sp-3);
        emu.writeMemory(ram(sp-3),new byte[]{(byte)page,0x40,0x10});
        emu.writeRegister("PPAGE",0xFE); // simulate CALL 0x9139,FE page selection
        emu.writeRegister("PC",0x7F9139L);
        until(0x4010);
        check(reg("SP")==sp && reg("PPAGE")==page, "output stack/page");
    }
    void tick() throws Exception {
        // Enter the REAL 100 ms PIT hook, with the ISR's I bit set.
        int sp=reg("SP"), ccr=reg("CCR"), d=reg("D"), page=reg("PPAGE");
        emu.writeRegister("CCR",ccr|16);
        emu.writeRegister("PC",0x7F4AE7L);
        until(0x4AEB);
        check(reg("SP")==sp && reg("D")==d && reg("PPAGE")==page, "timer preserves stack/D/page");
        check((reg("CCR")&16)!=0, "PIT must not enable interrupts");
        emu.writeRegister("CCR",ccr); // model hardware RTI, not Ghidra's CPU12-only frame
    }
    byte[] canIds(int id,boolean ext,boolean rtr) {
        return ext ? new byte[]{(byte)(id>>21),(byte)(((id>>13)&0xE0)|0x18|((id>>15)&7)),(byte)(id>>7),(byte)((id<<1)|(rtr?1:0))}
            : new byte[]{(byte)(id>>3),(byte)((id<<5)|(rtr?16:0)),0,0};
    }
    byte[] payload(int magic,int lease) {
        return new byte[]{(byte)(magic>>8),(byte)magic,0x11,0x22,0x33,0x44,(byte)(lease>>8),(byte)lease};
    }
    void frame(int id,boolean ext,int dlc,boolean rtr,int magic,int lease) throws Exception {
        emu.writeMemory(g(0x2A0),canIds(id,ext,rtr));
        emu.writeMemory(g(0x2A4),payload(magic,lease));
        emu.writeMemory(g(0x2AC),new byte[]{(byte)dlc});
    }
    void receive() throws Exception {
        // Execute receiver with a reserved hardware-frame area; restore interrupted
        // registers explicitly because Ghidra models RTI as CPU12, not CPU12X.
        String[] names={"PC","SP","D","IX","IY","CCR","PPAGE","RPAGE","EPAGE"};
        long[] saved=new long[names.length];
        for(int i=0;i<names.length;i++) saved[i]=emu.readRegister(names[i]).longValue();
        emu.writeRegister("SP",reg("SP")-16);
        emu.writeRegister("CCR",reg("CCR")|16);
        emu.writeRegister("PC",0x7F4D47L);
        until(0x55D5); // after acknowledge, before stock CLI / LEAS / RTI
        check(reg("SP")==saved[1]-16-23, "CAN stack frame");
        check((reg("CCR")&16)!=0, "CAN must stay masked through publication");
        check(reg("PPAGE")==saved[6], "CAN helper returns to interrupted page before RTI");
        for(int i=0;i<names.length;i++) emu.writeRegister(names[i],saved[i]);
    }
    // Commands arrive on CAN1 as RV-C proprietary DGN 0x18EF70xx. CAN4's 0x999
    // case is inert, so every command test drives the CAN1 receiver.
    void can1Mailbox(int id,boolean ext,int dlc,boolean rtr,byte[] data) throws Exception {
        emu.writeMemory(g(0x1A0),canIds(id,ext,rtr));
        emu.writeMemory(g(0x1A4),data);
        emu.writeMemory(g(0x1AC),new byte[]{(byte)dlc});
    }
    void can1Frame(int id,boolean ext,int dlc,boolean rtr,byte[] data) throws Exception {
        can1Mailbox(id,ext,dlc,rtr,data);
        String[] names={"PC","SP","D","IX","IY","CCR","PPAGE","RPAGE","EPAGE"};
        long[] saved=new long[names.length];
        for(int i=0;i<names.length;i++) saved[i]=emu.readRegister(names[i]).longValue();
        emu.writeRegister("SP",reg("SP")-16);
        emu.writeRegister("CCR",reg("CCR")|16);
        emu.writeRegister("PC",0x7F4BDAL);
        until(0x4D41); // after acknowledge, before stock CLI / LEAS / RTI
        check(reg("SP")==saved[1]-16-0x12, "CAN1 stack frame");
        check((reg("CCR")&16)!=0, "CAN1 must stay masked through publication");
        check(reg("PPAGE")==saved[6], "CAN1 helper returns to interrupted page before RTI");
        for(int i=0;i<names.length;i++) emu.writeRegister(names[i],saved[i]);
    }
    void can1Command(int magic,int dlc,boolean rtr,int lease) throws Exception {
        can1Frame(0x18EF7080,true,dlc,rtr,payload(magic,lease));
    }
    void command(int lease) throws Exception { can1Command(0xAA55,8,false,lease); }
    void relay(boolean on,String context) throws Exception {
        check(((byteAt(0x2350)&4)!=0)==on, context+" object");
        int port=emu.readMemory(g(0),1)[0]&255;
        check(((port&8)!=0)==on, context+" PA3");
        check((port&~8)==0xA5, context+" unrelated pins unchanged");
    }
    void basic() throws Exception {
        for(int stock=0;stock<=1;stock++) for(int lease:new int[]{0,1,2,256,65535}) {
            init(); byteAt(0x2350,stock); command(lease);
            relay(stock!=0 || lease!=0,"command "+stock+"/"+lease);
            check(word(0x2254)==lease,"command does not consume lease");
            if(lease!=0) { tick(); check(word(0x2254)==lease-1,"lease decrement"); relay(stock!=0 || lease>1,"tick"); }
            command(0); relay(stock!=0,"cancel preserves automatic heating");
            check((byteAt(0x2350)&1)==stock,"stock request preserved");
            emu.dispose();
        }
        init(); word(0x2250,0x028A); word(0x2254,0x3232); output(); tick();
        check(word(0x2254)==0x3232,"power-up defaults do not arm or age lease"); relay(false,"power-up"); emu.dispose();
        for(int ccr:new int[]{0,16,0xC0,0xD0}) {
            init(); emu.writeRegister("CCR",ccr); output();
            check(reg("CCR")==ccr,"output restores caller CCR"); emu.dispose();
        }
        for(int page:new int[]{0xE0,0xFC,0xFD,0xFE,0xFF}) {
            init(); emu.writeRegister("PPAGE",page);
            command(2); relay(true,"paged command");
            check(reg("PPAGE")==page,"CAN restores interrupted page");
            tick(); output(); relay(true,"paged tick/output");
            check(reg("PPAGE")==page,"helpers restore caller page");
            command(0); relay(false,"paged cancellation"); emu.dispose();
        }
        println("PASS: basic activation/expiry/cancel, stock OR semantics, 16-bit boundaries, CCR restoration");
        println("PASS: CAN/PIT/output calls preserve pages E0/FC/FD/FE/FF");
    }
    @Override public void run() throws Exception { loadImage(); basic(); println("V2 BASIC ACCEPTANCE PASSED"); }
}
