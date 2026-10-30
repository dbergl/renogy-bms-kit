// Acceptance of the settable charge voltage limit (make_patch.py --cvl,
// docs/charge-limits.md). No hardware access.
// Args: CVL global S19, stock global S19, status-candidate global S19 [, quick] [, max=570
// for a --cvl570 build, whose clamp passes 520..570; 560 by default].
//@category Test
import java.nio.file.*;
import java.util.*;

public class CvlSettingTest extends VerifiedHeaterHarness {
    static final int IR3=0x2674, LIMIT=0x23BC, ENABLE=0x23C0, PACK=0x25EE;
    static final int GET=0xBFE0, CMP=0xBFF1, DRIVER=0x6000, REF=0x6010;
    // The three splices, global, and the bytes each must hold in either image.
    static final long[] SITES={0x7FA8A4L,0x7F9537L,0x7FA974L};
    static final String[] STOCK_BYTES={"CC0223","8C0222","8C0222"};
    static final String[] CVL_BYTES={"16BFE0","16BFF1","16BFF1"};
    int cases;
    Trace trace;
    // RAM 0x2000-0x3FFF right after init, restored before each soc_calc pass.
    // Restored in 256-byte copies and read back. EmulatorHelper.writeMemory
    // adopts a buffer of 4 KB or more as emulator memory instead of copying it
    // (EmulatorQuirkProbe.java, Ghidra 12.0.4 and 12.1.4), so restoring the
    // whole 8 KB baseline in one call made it absorb every later pass's writes.
    byte[] baseline;
    boolean quick;     // optional 4th arg "quick": image identity and firmware section only

    static int cvlMax=560;   // cvl_get's upper bound: 560, or 570 with --cvl570
    static int limitFor(int ir3) { return ir3>=520 && ir3<=cvlMax ? ir3 : 547; }
    static int[] probes() {
        return new int[]{0,1,519,520,521,540,546,547,548,559,560,561,565,569,570,571,600,0x7FFF,0x8000,0xFFFF};
    }
    // NXP HCS12 CPU reference manual, CPD: D - M, sets N Z V C, others kept.
    static int cpd(int d,int m,int ccr) {
        int r=(d-m)&0xFFFF;
        boolean d15=(d&0x8000)!=0,m15=(m&0x8000)!=0,r15=(r&0x8000)!=0;
        int flags=(r15?8:0)|(r==0?4:0)
            |((d15&&!m15&&!r15)||(!d15&&m15&&r15)?2:0)
            |((!d15&&m15)||(m15&&r15)||(r15&&!d15)?1:0);
        return (ccr&~0x0F)|flags;
    }

    Map<Long,Integer> image(String path) throws Exception {
        Map<Long,Integer> m=new TreeMap<>();
        for(String line:Files.readAllLines(Path.of(path))) {
            if(!line.startsWith("S3")) continue;
            long at=Long.parseLong(line.substring(4,12),16);
            byte[] data=HexFormat.of().parseHex(line.substring(12,line.length()-2));
            for(int i=0;i<data.length;i++) m.put(at+i,data[i]&255);
        }
        return m;
    }
    String hex(long at,int n) throws Exception { return HexFormat.of().formatHex(emu.readMemory(g(at),n)).toUpperCase(); }

    // 1. The CVL image is the status candidate plus the splices and FE:BFE0-BFFF.
    void identity() throws Exception {
        Map<Long,Integer> cvl=image(getScriptArgs()[0]), base=image(getScriptArgs()[2]);
        Set<Long> allowed=new HashSet<>();
        for(long s:SITES) for(int i=0;i<3;i++) allowed.add(s+i);
        for(long a=0x7FBFE0L;a<0x7FC000L;a++) { allowed.add(a); check(!base.containsKey(a),"FE tail blank in candidate"); }
        Set<Long> all=new TreeSet<>(cvl.keySet()); all.addAll(base.keySet());
        int changed=0;
        for(long a:all) if(!Objects.equals(cvl.get(a),base.get(a))) {
            check(allowed.contains(a),"unexpected difference at "+Long.toHexString(a)); changed++;
        }
        check(changed==9+32,"exactly 9 spliced + 32 added bytes differ, got "+changed);
        for(int i=0;i<3;i++) check(hex(SITES[i],3).equals(CVL_BYTES[i]),"splice "+i+" bytes");
        println("PASS: image = status candidate + 3 splices + FE:BFE0-BFFF ("+changed+" bytes)");
    }

    void prepare() throws Exception {
        init(); counters();
        emu.writeRegister("PPAGE",0xFE);
        // Drivers in unprogrammed FD flash: JSR target; and the stock CPD #0x222.
        emu.writeMemory(g(0x7F0000L|DRIVER),new byte[]{0x16,(byte)0xBF,(byte)0xE0});
        emu.writeMemory(g(0x7F0000L|DRIVER+3),new byte[]{0x16,(byte)0xBF,(byte)0xF1});
        emu.writeMemory(g(0x7F0000L|REF),new byte[]{(byte)0x8C,0x02,0x22});
        trace=new Trace(emu,0x2200);
        trace.allowed=new HashSet<>(); trace.enforce=true;   // no write outside the stack
    }
    void call(int entry,int d,int ccr) throws Exception {
        emu.writeRegister("SP",0x2200); emu.writeRegister("D",d); emu.writeRegister("CCR",ccr);
        emu.writeRegister("IX",0x1357); emu.writeRegister("IY",0x2468);
        emu.writeRegister("PC",0x7F0000L|entry);
        runTo(entry+3,64);
        check(reg("SP")==0x2200 && reg("IX")==0x1357 && reg("IY")==0x2468 && reg("PPAGE")==0xFE,"registers/stack restored");
    }

    // 2. cvl_get over every IR3 value.
    void getSweep(int from,int to) throws Exception {
        for(int v=from;v<=to;v++) {
            word(IR3,v);
            call(DRIVER,0xA5A5,0xC0);
            check(reg("D")==limitFor(v),"cvl_get IR3="+v+" D="+reg("D"));
            cases++;
        }
    }
    // 3. cvl_cmp equals CPD #(limit-1): D, N Z V C and the other CCR bits.
    void cmpCase(int ir3,int d,int ccr) throws Exception {
        word(IR3,ir3);
        call(DRIVER+3,d,ccr);
        int want=cpd(d,limitFor(ir3)-1,ccr);
        check(reg("D")==d,"cvl_cmp preserves D");
        check(reg("CCR")==want,"cvl_cmp IR3="+ir3+" D="+d+" ccr="+Integer.toHexString(ccr)
            +" got="+Integer.toHexString(reg("CCR"))+" want="+Integer.toHexString(want));
        cases++;
    }
    void cmpSweep(int[] ir3s,int extra) throws Exception {
        Random r=new Random(0x5EED);
        int[] ccrs={0x00,0xD0,0x1F,0xC5};
        for(int ir3:ir3s) {
            int t=limitFor(ir3)-1;
            List<Integer> ds=new ArrayList<>(List.of(0,1,0x7FFE,0x7FFF,0x8000,0x8001,0xFFFE,0xFFFF,t^0x8000));
            for(int k=-4;k<=4;k++) ds.add((t+k)&0xFFFF);
            for(int k=0;k<extra;k++) ds.add(r.nextInt(65536));
            for(int d:ds) for(int c:ccrs) cmpCase(ir3,d,c);
        }
    }
    // The emulator's own CPD agrees with the reference on every D (model check).
    void cpdModel() throws Exception {
        for(int d=0;d<65536;d++) {
            for(int c:new int[]{0x00,0xCF}) {
                emu.writeRegister("D",d); emu.writeRegister("CCR",c);
                emu.writeRegister("PC",0x7F0000L|REF); step();
                check(reg("CCR")==cpd(d,0x222,c),"emulator CPD #0x222 D="+d);
            }
        }
        println("PASS: emulator CPD #0x222 matches the reference for all 65536 D, two CCR seeds");
    }

    // 4. Real firmware: register 0x138F, register 0x1391, soc_calc rule 1.
    void variant(boolean stock) throws Exception {
        init();
        baseline=emu.readMemory(ram(0x2000),0x2000);
        if(stock) for(long s:SITES) restoreStock(emu,s,s+3);
        for(int i=0;i<3;i++) check(hex(SITES[i],3).equals(stock?STOCK_BYTES[i]:CVL_BYTES[i]),"variant bytes");
    }
    int limitRegister(int ir3) throws Exception {
        word(IR3,ir3); word(LIMIT,0);
        emu.writeRegister("SP",0x21F2); emu.writeRegister("PPAGE",0xFE);
        emu.writeRegister("PC",0x7FA8A4L); runTo(0xA8AA,100);
        check(reg("SP")==0x21F2 && reg("PPAGE")==0xFE,"138F stack/page");
        return word(LIMIT);
    }
    int enableRegister(int ir3,int pack) throws Exception {
        word(IR3,ir3); word(PACK,pack); word(ENABLE,0xBEEF);
        emu.writeRegister("SP",0x21F2); emu.writeRegister("PPAGE",0xFE);
        emu.writeRegister("PC",0x7FA971L); runTo(0xA985,100);
        check(reg("SP")==0x21F2 && reg("PPAGE")==0xFE,"1391 stack/page");
        return word(ENABLE);
    }
    // One soc_calc pass in charge mode, current -2 A, cells below every cell rule.
    void socState(int ir3,int pack,int count) throws Exception {
        for(int off=0;off<0x2000;off+=0x100) {
            byte[] chunk=Arrays.copyOfRange(baseline,off,off+0x100);
            emu.writeMemory(ram(0x2000+off),chunk);
            check(Arrays.equals(emu.readMemory(ram(0x2000+off),0x100),chunk),"baseline restored at "+Integer.toHexString(0x2000+off));
        }
        word(IR3,ir3); word(0x2BE6,1); word(PACK,pack);
        word(0x22A6,0xC000); word(0x22A8,0);          // -2.0f: tapering charge
        word(0x236E,0x4248); word(0x2370,0);          // SoC 50.0f
        word(0x2386,count); word(0x25E4,3300);
        for(int a:new int[]{0x2414,0x2417,0x2418,0x2419,0x2383,0x2384,0x2382,0x2624}) byteAt(a,0);
    }
    void socPass() throws Exception {
        emu.writeRegister("SP",0x2200); emu.writeRegister("PPAGE",0xFE);
        farEntry(0x94C1); runTo(0x4010,20000);
        check(reg("SP")==0x2200,"soc_calc stack balance");
    }
    boolean full() throws Exception { return word(0x236E)==0x42C8 && word(0x2370)==0 && (byteAt(0x2624)&2)!=0; }
    byte[] ramImage() throws Exception {
        byte[] low=emu.readMemory(ram(0x2000),0x100), high=emu.readMemory(ram(0x2200),0x1E00);
        byte[] all=Arrays.copyOf(low,low.length+high.length);
        System.arraycopy(high,0,all,low.length,high.length); return all;
    }
    void firmware() throws Exception {
        int[] ir3s=probes();
        // Stock reference values, from the stock bytes.
        variant(true);
        int stockLimit=limitRegister(0);
        check(stockLimit==547,"stock 138F is 54.7 V");
        Map<Integer,Integer> stockEnable=new HashMap<>();
        for(int p=530;p<=560;p++) stockEnable.put(p,enableRegister(0,p));
        Map<Integer,byte[]> stockSoc=new HashMap<>();
        for(int p:new int[]{0,500,538,539,540,545,546,547,600,0xFFFF}) {
            socState(0,p,3000); socPass(); stockSoc.put(p,ramImage());
        }
        emu.dispose();
        variant(false);
        for(int ir3:ir3s) {
            check(limitRegister(ir3)==limitFor(ir3),"138F IR3="+ir3); cases++;
            int t=limitFor(ir3)-1;
            for(int p:new int[]{0,t-2,t-1,t,t+1,t+2,0x7FFF,0x8000,0xFFFF}) {
                check(enableRegister(ir3,p)==(Integer.compareUnsigned(p,t)>=0?1:0),"1391 IR3="+ir3+" pack="+p); cases++;
            }
        }
        // IR3 = 0 is stock: identical registers and identical soc_calc RAM effects.
        for(int p=530;p<=560;p++) { check(enableRegister(0,p)==stockEnable.get(p),"1391 equals stock pack="+p); cases++; }
        for(var e:stockSoc.entrySet()) {
            socState(0,e.getKey(),3000); socPass();
            byte[] got=ramImage(),want=e.getValue();
            int at=Arrays.mismatch(got,want);
            check(at<0,"soc_calc RAM equals stock pack="+e.getKey()+(at<0?"":String.format(
                " first difference local %04X patched=%02X stock=%02X",at<0x100?0x2000+at:0x2100+at,got[at],want[at])));
            cases++;
        }
        // Rule 1 threshold follows the limit.
        for(int ir3:ir3s) {
            int t=limitFor(ir3)-1;
            for(int p:new int[]{t-1,t}) {
                socState(ir3,p,3000); socPass();
                check(full()==(p>=t),"rule 1 IR3="+ir3+" pack="+p);
                check(word(0x2386)==0,"rule 1 count reset either way"); cases++;
            }
        }
        // Full 5-minute run at a lowered limit: fires on pass 3001, not before.
        socState(540,539,0);
        for(int i=1;i<=3000;i++) { socPass(); check(!full(),"not full before pass 3001"); }
        socPass(); check(full(),"full on pass 3001 at 53.9 V with IR3=540"); cases++;
        // A dip below threshold restarts the count.
        socState(540,539,2999); socPass(); word(PACK,538); socPass(); check(word(0x2386)==0,"dip resets count");
        word(PACK,539); socPass(); check(!full() && word(0x2386)==1,"count restarts after dip"); cases++;
        emu.dispose();
        println("PASS: 138F/1391/soc_calc rule 1 follow IR3; IR3=0 reproduces stock RAM effects");
    }

    // 5. Stack, measured on the real call sites.
    int depth(boolean stock,int which) throws Exception {
        variant(stock);
        if(which==0) { socState(0,546,3000); emu.writeRegister("SP",0x2200); emu.writeRegister("PPAGE",0xFE);
            farEntry(0x94C1); counters(); runTo(0x4010,20000); }
        else { word(PACK,546); emu.writeRegister("SP",0x2200); emu.writeRegister("PPAGE",0xFE);
            emu.writeRegister("PC",0x7FA971L); counters(); runTo(0xA985,100); }
        int bytes=0x2200-minimumSP; emu.dispose(); return bytes;
    }
    void stack() throws Exception {
        for(int which=0;which<2;which++) {
            int s=depth(true,which),p=depth(false,which);
            println("MEASURE "+(which==0?"soc_calc call (incl. 3-byte CALL frame)":"1391 block")+": stock="+s+" patched="+p+" bytes");
            // Peak is the outer JSR + PSHD + (inner JSR, popped before the second PSHD): 6.
            if(which==1) check(s==0 && p==6,"1391 block: stock 0, patched exactly the cvl_cmp peak of 6");
            else check(p==s,"soc_calc high-water unchanged: cvl_cmp peaks below the float-compare calls");
        }
    }

    // 6. Negative controls: each breaks one property and must be caught.
    void patchByte(long at,int expect,int value) throws Exception {
        check((emu.readMemory(g(at),1)[0]&255)==expect,"control targets the intended byte "+Long.toHexString(at));
        emu.writeMemory(g(at),new byte[]{(byte)value});
    }
    void controls() throws Exception {
        rejects("clamp upper bound "+cvlMax+" -> "+(cvlMax+1),()->{
            prepare(); patchByte(0x7FBFEAL,cvlMax&255,(cvlMax+1)&255); getSweep(cvlMax-5,cvlMax+5); });
        rejects("threshold = limit, not limit - 0.1 V (SUBD #0)",()->{
            prepare(); patchByte(0x7FBFF7L,0x01,0x00); cmpSweep(new int[]{0},0); });
        rejects("LEAS 4,SP dropped (stack imbalance)",()->{
            prepare(); patchByte(0x7FBFFDL,0x1B,0xA7); patchByte(0x7FBFFEL,0x84,0xA7); cmpSweep(new int[]{0},0); });
        rejects("stock 54.6 V compare left in soc_calc at IR3=540",()->{
            variant(false); restoreStock(emu,SITES[1],SITES[1]+3);
            socState(540,539,3000); socPass(); check(full(),"lowered limit fires rule 1"); });
    }

    @Override public void run() throws Exception {
        for(String a:getScriptArgs()) if(a.startsWith("max=")) cvlMax=Integer.parseInt(a.substring(4));
        println("cvl_get upper bound expected: "+cvlMax);
        loadImage();
        disassemble(g(0x7FBFE0L)); disassemble(g(0x7FBFF1L));
        init(); identity(); emu.dispose();
        quick=Arrays.asList(getScriptArgs()).contains("quick");
        if(quick) { firmware(); println("CVL QUICK PASSED: "+cases+" cases"); return; }
        prepare(); cpdModel(); emu.dispose();
        prepare(); getSweep(0,65535); emu.dispose();
        println("PASS: cvl_get over all 65536 IR3 values, no write outside the stack");
        prepare();
        cmpSweep(probes(),200);
        for(int d=0;d<65536;d++) cmpCase(0,d,0xD0);
        emu.dispose();
        println("PASS: cvl_cmp = CPD #(limit-1) incl. all 65536 D at IR3=0; D, IX, IY, SP, PPAGE kept; no write outside the stack");
        firmware();
        stack();
        controls();
        println("CVL SETTING PASSED: "+cases+" cases, "+assertions+" assertions");
    }
}
