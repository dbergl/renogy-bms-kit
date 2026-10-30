// Acceptance of the charge current slew limit (make_patch.py --cvl --slew,
// docs/charge-limits.md). No hardware access.
// Args: slew global S19, stock global S19, CVL global S19, and optionally the
// fall rate the image was built with: 128 (default, --slew, flashed 76232cb7...)
// or 256 (--slew256).
//@category Test
import java.nio.file.*;
import java.util.*;

public class ChargeSlewTest extends VerifiedHeaterHarness {
    static final int LIMIT=0x23BE, FRAME=0x21F2;
    static final long SPLICE=0x7FA96EL, CODE=0x7F57E0L;
    static final int[] LADDER={0,-420,-1200,-2000,-2100,-4200,-6300,-10500};
    int cases;
    static int RATE=128;          // set from the 4th argument before anything runs

    // The reviewed algorithm: zero and increases pass; a fall is limited to a
    // step per pass from no lower than -90.00 A. Values are signed 0.01 A,
    // charging negative. The step is what the bytes compute: at 1/128,
    // ASLD / NEGA / SEX A,D give -(O asr 7) = ceil(|O|/128) for -16256 <= O < 16384;
    // at 1/256, LDAA / NEGA / SEX A,D give -(O asr 8) = ceil(|O|/256) for
    // -32512 <= O < 32768. Both cover every reachable O (checked in properties());
    // outside that range it is whatever the bytes give.
    static int step(int o) {
        int high=RATE==128 ? ((o<<1)>>8)&0xFF : (o>>8)&0xFF;    // A after ASLD, or the high byte
        return (byte)(-high);                                    // NEGA, SEX A,D
    }
    static int shift() { return RATE==128 ? 7 : 8; }
    static int lowest() { return RATE==128 ? -16256 : -32512; }
    // Byte addresses inside the routine, which moves up one byte at 1/256.
    static long at(long a128) { return RATE==128 || a128<=0x7F57E1L ? a128 : a128-1; }
    static int model(int o,int t) {
        o=(short)o; t=(short)t;
        if(t==0) return 0;
        int f=Math.max((short)(o+step(o)),-9000);
        return Math.min(t,f);
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

    // 1. The slew image is the CVL image plus the splice and 0x57E0-0x57FF.
    void identity() throws Exception {
        Map<Long,Integer> slew=image(getScriptArgs()[0]), cvl=image(getScriptArgs()[2]);
        Set<Long> allowed=new HashSet<>();
        for(int i=0;i<3;i++) allowed.add(SPLICE+i);
        for(long a=CODE;a<CODE+32;a++) { allowed.add(a); check(!cvl.containsKey(a),"0x57E0 window blank in the CVL image"); }
        Set<Long> all=new TreeSet<>(slew.keySet()); all.addAll(cvl.keySet());
        int changed=0;
        for(long a:all) if(!Objects.equals(slew.get(a),cvl.get(a))) {
            check(allowed.contains(a),"unexpected difference at "+Long.toHexString(a)); changed++;
        }
        check(changed==3+32,"exactly 3 spliced + 32 added bytes differ, got "+changed);
        check(slew.get(SPLICE)==0x16 && slew.get(SPLICE+1)==0x57 && slew.get(SPLICE+2)==0xE0,"splice is JSR 0x57E0");
        check(cvl.get(SPLICE)==0x7C && cvl.get(SPLICE+1)==0x23 && cvl.get(SPLICE+2)==0xBE,"CVL image has stock STD 0x23BE");
        println("PASS: image = CVL image + JSR splice at FE:A96E + 0x57E0-0x57FF ("+changed+" bytes)");
    }

    // 2. The routine through its real call site: FE:A96C LDD 2,SP / JSR, to A971.
    Trace trace;
    void prepare() throws Exception {
        init(); counters();
        emu.writeRegister("PPAGE",0xFE);
        trace=new Trace(emu,FRAME+16);
        trace.allowed=new HashSet<>(List.of(RAM+LIMIT,RAM+LIMIT+1)); trace.enforce=true;
    }
    int publish(int old,int target) throws Exception {
        word(LIMIT,old&0xFFFF);
        word(FRAME+2,target&0xFFFF);
        emu.writeRegister("SP",FRAME); emu.writeRegister("IX",0x1357); emu.writeRegister("IY",0x2468);
        emu.writeRegister("PC",0x7FA96CL);
        runTo(0xA971,40);
        check(reg("SP")==FRAME && reg("IX")==0x1357 && reg("IY")==0x2468 && reg("PPAGE")==0xFE,"registers/stack restored");
        check(word(FRAME+2)==(target&0xFFFF),"caller's local untouched");
        return word(LIMIT);
    }
    void against(int old,int target) throws Exception {
        int got=publish(old,target), want=model(old,target)&0xFFFF;
        check(got==want,String.format("old=%d target=%d got=%d want=%d",(short)old,(short)target,(short)got,(short)want));
        cases++;
    }
    void sweep() throws Exception {
        prepare();
        for(int t:LADDER) for(int o=0;o<65536;o++) against(o,t);
        println("PASS: every previous value (65536) x every ladder output (8) matches the model");
        for(int o:new int[]{0,-1,-127,-128,-129,-420,-4200,-9000,-9001,-10500,-16384,-32768,32767,1})
            for(int t=0;t<65536;t+=7) against(o,t);
        Random r=new Random(0x51E3);
        for(int k=0;k<20000;k++) against(r.nextInt(65536),r.nextInt(65536));
        emu.dispose();
        println("PASS: arbitrary target sweeps and 20000 random pairs match the model; only 0x23BE written");
    }

    // 3. Properties, independent of the model, over the reachable range.
    void properties() {
        for(int o=lowest();o<(RATE==128?16384:32768);o++)
            check(step(o)==-Math.floorDiv(o,RATE),"step = -(O asr "+shift()+") at "+o);
        // Unreachable (corrupt) previous values still publish between the ladder
        // value and the 90 A clamp, never more.
        for(int o=-32768;o<32768;o++) for(int t:LADDER) if(t!=0) {
            int n=model(o,t); check(n<=t && n>=Math.min(t,-9000),"bounded for corrupt O="+o);
        }
        for(int o=-10500;o<=0;o++) for(int t:LADDER) {
            int n=model(o,t);
            check(t!=0 || n==0,"zero passes at once");
            check(n<=t,"never publishes less current than the ladder asks");
            check(n<=0,"never publishes a discharge value");
            int from=Math.max(o,-9000);
            if(t<=from) check(n==t,"an increase, or a fall to within the floor, passes at once");
            if(t!=0 && n>from) check(n-from<=(-from+RATE-1)/RATE,"a fall is at most ceil(|O|/"+RATE+") per pass");
            if(t<0 && n>o) check(n>=-9000,"a fall never publishes more than 90 A");
            cases++;
        }
        // Worst ratio the regulator can see between successive polls 1 s apart.
        double worst=0;
        for(int start=-9000;start<=-420;start++) {
            int v=start; for(int k=0;k<10;k++) v=model(v,-420);
            if(v<0) worst=Math.max(worst,(double)start/v);
        }
        check(worst<(RATE==128?1.10:1.06),"no 1 s window falls by the design limit or more, worst "+worst);
        println(String.format("PASS: properties hold for every previous value -105.00..0 A; worst 1 s ratio %.4f",worst));
    }

    // 4. The real current-selection block, pass by pass.
    int pass(int mv,int t,int alarm) throws Exception {
        word(0x2BE6,1); word(0x25E4,mv); word(0x233C,t);
        word(0x23A6,0); word(0x23AC,0);
        for(int a:new int[]{0x2418,0x2414,0x2624,0x2415,0x2410}) byteAt(a,a==alarm?2:0);
        emu.writeRegister("SP",FRAME); emu.writeRegister("PPAGE",0xFE);
        emu.writeRegister("PC",0x7FA8AAL);
        runTo(0xA971,2000);
        check(reg("SP")==FRAME && reg("PPAGE")==0xFE,"stack/page restored");
        return (short)word(LIMIT);
    }
    void firmware(boolean stock) throws Exception {
        init();
        if(stock) restoreStock(emu,SPLICE,SPLICE+3);
    }
    int ticks(int from,int mv,int t,int target,List<Integer> seen) throws Exception {
        word(LIMIT,from&0xFFFF);
        for(int k=1;k<=1000;k++) {
            int v=pass(mv,t,0); seen.add(v);
            if(v==target) return k;
        }
        throw new AssertionError("did not reach "+target);
    }
    void scenarios() throws Exception {
        // Stock: 105 A to 42 A in one pass - the step that zeroes the field.
        firmware(true);
        word(LIMIT,-10500&0xFFFF);
        check(pass(3400,250,0)==-4200,"stock cuts 105 -> 42 A in one pass");
        emu.dispose();

        firmware(false);
        // Steady state equals the stock ladder at every point of the curve.
        for(int t:new int[]{-1,0,1,49,50,250,500,501,549,550,551})
            for(int mv:new int[]{3000,3399,3400,3429,3430,3459,3460,3499,3500,4000}) {
                int want=Can1ChargeCurve(mv,t);
                word(LIMIT,want&0xFFFF);
                check(pass(mv,t,0)==want,"steady state mv="+mv+" t="+t); cases++;
            }
        // 105 A -> 42 A: first pass publishes the 90 A clamp, then a smooth fall.
        List<Integer> seen=new ArrayList<>();
        int n=ticks(-10500,3400,250,-4200,seen);
        check(seen.get(0)==-9000,"first pass publishes -90.00 A");
        for(int k=1;k<seen.size();k++) {
            int a=seen.get(k-1),b=seen.get(k);
            check(b>a && b-a<=(-a+RATE-1)/RATE,"pass "+k+": "+a+" -> "+b);
            if(k>=10) check((double)seen.get(k-10)/b<(RATE==128?1.10:1.06),"1 s ratio at pass "+k);
        }
        int want=1; for(int v=-9000;v!=-4200;want++) v=model(v,-4200);
        println("MEASURE 105 -> 42 A: "+n+" passes ("+(n/10.0)+" s), one-pass max fall "
            +String.format("%.3f%%",100.0*step(-9000)/9000));
        check(n==want,"105 -> 42 A in exactly the model's "+want+" passes");
        check(RATE==128 ? n>=95 && n<=100 : n>=188 && n<=196,"105 -> 42 A in about "+(RATE==128?"9.8":"19.2")+" s");
        // Every other ladder step, and the hot base.
        int[][] steps={{3430,250,-4200,-2100},{3460,250,-2100,-1200},{3500,250,-1200,-420},
                       {3300,510,-10500,-6300},{3300,40,-10500,-2000}};
        for(int[] s:steps) {
            seen.clear();
            int k=ticks(s[2],s[0],s[1],s[3],seen);
            println("MEASURE "+(-s[2]/100.0)+" -> "+(-s[3]/100.0)+" A: "+k+" passes");
            cases++;
        }
        // Mid-fall: an L2 alarm and the 0 / 55 C gates stop charging on the next pass.
        for(int alarm:new int[]{0x2418,0x2414,0x2624,0x2415,0x2410}) {
            word(LIMIT,-6000&0xFFFF);
            check(pass(3400,250,alarm)==0,"alarm "+Integer.toHexString(alarm)+" stops at once"); cases++;
        }
        for(int t:new int[]{0,-5,550,600}) {
            word(LIMIT,-6000&0xFFFF);
            check(pass(3400,t,0)==0,"temperature "+t+" stops at once"); cases++;
        }
        // Recovery after a stop, and after a partial fall, is immediate.
        word(LIMIT,0); check(pass(3300,250,0)==-10500,"0 -> 105 A at once");
        word(LIMIT,-6000&0xFFFF); check(pass(3300,250,0)==-10500,"60 -> 105 A at once"); cases+=2;
        emu.dispose();
        println("PASS: real selection block - steady state = stock, falls slewed, stops and rises immediate");
    }
    static int Can1ChargeCurve(int mv,int t) {
        if(t<=0 || t>=550) return 0;
        if(t<50) return -2000;
        if(mv<3400) return t<=500?-10500:-6300;
        if(mv<3430) return -4200;
        if(mv<3460) return -2100;
        if(mv<3500) return -1200;
        return -420;
    }

    // 5. Stack, on the real block.
    void stack() throws Exception {
        for(boolean stock:new boolean[]{true,false}) {
            firmware(stock);
            word(LIMIT,-10500&0xFFFF);
            emu.writeRegister("SP",FRAME); counters();
            pass(3400,250,0);
            int whole=FRAME-minimumSP;
            word(LIMIT,-10500&0xFFFF); word(FRAME+2,-4200&0xFFFF);
            emu.writeRegister("SP",FRAME); emu.writeRegister("PC",0x7FA96CL); counters();
            runTo(0xA971,40);
            int store=FRAME-minimumSP;
            println("MEASURE "+(stock?"stock":"patched")+": selection block high-water "+whole+" bytes, store "+store+" bytes");
            check(store==(stock?0:2),"store: stock 0, patched one JSR frame");
            check(whole>=2,"the block's own calls are deeper than the JSR");
            emu.dispose();
        }
    }

    // 6. Negative controls: each breaks one property and must be caught.
    void patchByte(long at,int expect,int value) throws Exception {
        check((emu.readMemory(g(at),1)[0]&255)==expect,"control targets the intended byte "+Long.toHexString(at));
        emu.writeMemory(g(at),new byte[]{(byte)value});
    }
    void controls() throws Exception {
        rejects("zero target slewed instead of immediate (BEQ removed)",()->{
            firmware(false); patchByte(0x7F57E0L,0x27,0xA7); patchByte(0x7F57E1L,RATE==128?0x18:0x17,0xA7);
            word(LIMIT,-6000&0xFFFF); check(pass(3400,250,0x2418)==0,"alarm stops"); });
        rejects("90 A floor removed",()->{
            firmware(false); patchByte(at(0x7F57EFL),0x2C,0x20);
            word(LIMIT,-10500&0xFFFF); check(pass(3400,250,0)==-9000,"first pass -90 A"); });
        if(RATE==128) rejects("step doubled (ASLD -> NOP leaves A = high byte)",()->{
            prepare(); patchByte(0x7F57E5L,0x59,0xA7); for(int o=-9000;o<=0;o+=37) against(o,-420); });
        else rejects("step taken from T, not O (LDAA -> LDAB leaves A = T's high byte)",()->{
            prepare(); patchByte(0x7F57E2L,0xB6,0xF6); for(int o=-9000;o<=0;o+=37) against(o,-420); });
        rejects("splice reverted to the stock store",()->{
            firmware(false); restoreStock(emu,SPLICE,SPLICE+3);
            word(LIMIT,-10500&0xFFFF); check(pass(3400,250,0)==-9000,"first pass -90 A"); });
        rejects("min/max swapped (BLT -> BGT): an increase is slewed",()->{
            prepare(); patchByte(at(0x7F57F6L),0x2D,0x2E); against(-4200,-10500); against(-6000,-4200); });
    }

    @Override public void run() throws Exception {
        String[] args=getScriptArgs();
        RATE=args.length>3 ? Integer.parseInt(args[3]) : 128;
        check(RATE==128 || RATE==256,"rate is 128 or 256");
        println("Fall rate under test: 1/"+RATE+" per pass");
        loadImage();
        disassemble(g(CODE));
        for(long a=CODE;a<CODE+30;) {
            var ins=currentProgram.getListing().getInstructionAt(g(a));
            if(ins==null) { a++; continue; }
            println(String.format("%06X %s",a,ins)); a+=ins.getLength();
        }
        init(); identity(); emu.dispose();
        properties();
        sweep();
        scenarios();
        stack();
        controls();
        println("CHARGE SLEW PASSED: "+cases+" cases, "+assertions+" assertions");
    }
}
