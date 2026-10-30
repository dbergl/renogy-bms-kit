// Acceptance of undervoltage charging (make_patch.py --cvl --slew256 --uvcharge,
// docs/charge-limits.md). No hardware access.
// Args: uvcharge global S19, stock global S19, slew256 global S19.
//@category Test
import java.nio.file.*;
import java.util.*;

public class UvChargeTest extends VerifiedHeaterHarness {
    static final int LIMIT=0x23BE, FRAME=0x21F2, CELLUV=0x2417, PACKUV=0x2419, MODE=0x2BE6,
        HOLD=0x2502, CELL_COUNT=0x24DE, PACK_COUNT=0x24F2, DCH=0x25AF;
    static final long CODE=0x7F3780L, CAP=0x7FA96CL, COPY=0x7FAC0AL, MODE_SPLICE=0x7F500CL;
    static final int L2_CAP=-4200, L3_CAP=-2100, HOLD_WARN=2900, COUNT_WARN=60, DCH_WARN=1, HIDE=0xFA;
    static final int[] LADDER={0,-420,-1200,-2000,-2100,-4200,-6300,-10500};
    int cases;

    // ---- the reviewed design, as a model
    // A PA1 cut for undervoltage L3 may be near: 290 s into the charge-mode
    // hold; or 6 s into the 10 s countdown, unless charging with no net
    // discharge being counted (0x25AF, mode_detect_chg_dch: 30 ticks of 1 A
    // discharge end charge mode, and with it the hold, in the same pass).
    static boolean soon(int mode,int hold,int cell,int pack,int dch) {
        boolean count=(cell&0xFFFF)>=COUNT_WARN || (pack&0xFFFF)>=COUNT_WARN;
        return (mode&0xFFFF)==1 ? (hold&0xFFFF)>=HOLD_WARN || ((dch&0xFF)>=DCH_WARN && count) : count;
    }
    static boolean soon(int mode,int hold,int cell,int pack) { return soon(mode,hold,cell,pack,0); }
    // The request after the caps, before the slew. Charging is negative.
    static int capped(int t,int uv,boolean soon) {
        t=(short)t;
        if(soon || t==0) return 0;
        int level=(uv>>6)&3;
        if(level>=2) t=Math.max(t,L2_CAP);
        if(level==3) t=Math.max(t,L3_CAP);
        return t;
    }
    // ChargeSlewTest's 1/256 model.
    static int slew(int o,int t) {
        o=(short)o; t=(short)t;
        if(t==0) return 0;
        int f=Math.max((short)(o+(byte)(-((o>>8)&0xFF))),-9000);
        return Math.min(t,f);
    }
    static int published(int o,int t,int uv,boolean soon) { return slew(o,capped(t,uv,soon)); }

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

    // 1. The image is the running slew256 image plus two splices and FC:B780-B7FF.
    void identity() throws Exception {
        Map<Long,Integer> uv=image(getScriptArgs()[0]), base=image(getScriptArgs()[2]);
        Set<Long> allowed=new HashSet<>();
        for(int i=0;i<5;i++) allowed.add(CAP+i);
        for(int i=0;i<12;i++) allowed.add(COPY+i);
        for(int i=0;i<6;i++) allowed.add(MODE_SPLICE+i);
        for(long a=CODE;a<CODE+128;a++) { allowed.add(a); check(!base.containsKey(a),"FC:B780 window blank in the slew256 image"); }
        Set<Long> all=new TreeSet<>(uv.keySet()); all.addAll(base.keySet());
        int changed=0;
        for(long a:all) if(!Objects.equals(uv.get(a),base.get(a))) {
            check(allowed.contains(a),"unexpected difference at "+Long.toHexString(a)); changed++;
        }
        check(base.keySet().stream().allMatch(uv::containsKey),"every base address still programmed");
        check(changed<=5+12+6+128,"at most the three splices and the 128-byte window differ, got "+changed);
        int[] cap={0x4A,0xB7,0xBC,0xFC,0xA7};
        for(int i=0;i<5;i++) check(uv.get(CAP+i)==cap[i],"cap splice is CALL 0xB7BC,FC / NOP");
        int[] copy={0x4A,0xB7,0xAA,0xFC,0xE6,0x03,0xA4,0x02,0x7C,0x23,0xC6,0xA7};
        for(int i=0;i<12;i++) check(uv.get(COPY+i)==copy[i],"copy splice is CALL 0xB7AA,FC / LDAB 3,X / ANDA 2,X / STD 23C6 / NOP");
        int[] mode={0x4A,0xB7,0xED,0xFC,0xA7,0xA7};
        for(int i=0;i<6;i++) check(uv.get(MODE_SPLICE+i)==mode[i],"mode splice is CALL 0xB7ED,FC / 2 x NOP");
        int[] baseCap={0xEC,0x82,0x16,0x57,0xE0};
        for(int i=0;i<5;i++) check(base.get(CAP+i)==baseCap[i],"base has LDD 2,SP / JSR slew");
        int[] baseMode={0x7C,0x2B,0xE6,0xE6,0xE0,0x28};
        for(int i=0;i<6;i++) check(base.get(MODE_SPLICE+i)==baseMode[i],"base has STD 0x2BE6 / LDAB 0x28,IX");
        println("PASS: image = slew256 image + CALL splices at FE:A96C, FE:AC0A and 500C + FC:B780-B7FF ("+changed+" bytes)");
    }

    void state(int mode,int hold,int cell,int pack,int uv,int dch) throws Exception {
        word(MODE,mode&0xFFFF); word(HOLD,hold&0xFFFF);
        word(CELL_COUNT,cell&0xFFFF); word(PACK_COUNT,pack&0xFFFF); byteAt(CELLUV,uv); byteAt(DCH,dch);
    }
    void state(int mode,int hold,int cell,int pack,int uv) throws Exception { state(mode,hold,cell,pack,uv,0); }

    // 2. The register copy through its real site: FE:AC04 .. AC16.
    Trace trace;
    void prepareCopy() throws Exception {
        init(); counters();
        emu.writeRegister("PPAGE",0xFE);
        trace=new Trace(emu,FRAME+16);
        trace.allowed=new HashSet<>();
        for(int a=0x23C2;a<0x23C8;a++) trace.allowed.add(RAM+a);
        trace.enforce=true;
    }
    int[] copy(int w1392,int w1393,int w1394) throws Exception {
        word(0x2728,w1392); word(0x272A,w1393); word(0x272C,w1394);
        emu.writeRegister("SP",FRAME); emu.writeRegister("IY",0x2468);
        emu.writeRegister("PC",0x7FAC04L);
        runTo(0xAC16,80);
        check(reg("SP")==FRAME && reg("IY")==0x2468 && reg("PPAGE")==0xFE,"copy: stack, IY and page restored");
        check(word(0x2728)==w1392 && word(0x272A)==w1393 && word(0x272C)==w1394,"source table untouched");
        return new int[]{word(0x23C2),word(0x23C4),word(0x23C6)};
    }
    void copyCase(int mode,int hold,int cell,int pack,int dch,int w1392,int w1393,int w1394) throws Exception {
        state(mode,hold,cell,pack,0,dch);
        int[] got=copy(w1392,w1393,w1394);
        int and=soon(mode,hold,cell,pack,dch) ? 0xFFFF : (HIDE<<8)|0xFF;
        check(got[0]==w1392,"1392 copied unchanged");
        check(got[1]==(w1393&and),String.format("1393 %04X -> %04X",w1393,got[1]));
        check(got[2]==(w1394&and),String.format("1394 %04X -> %04X",w1394,got[2]));
        cases++;
    }
    static final int[] MODES={0,1,2,3,0x0101,0xFFFF};
    static final int[] COUNTS={0,1,59,60,61,99,100,2899,2900,2901,3000,0x8000,0xFFFF};
    static final int[] DEBOUNCE={0,1,30,0xFF};
    void copies() throws Exception {
        prepareCopy();
        // Every mode x every boundary of the three counters x the discharge debounce.
        for(int m:MODES) for(int h:COUNTS) for(int c:COUNTS) for(int p:new int[]{0,59,60,0xFFFF}) for(int d:DEBOUNCE)
            copyCase(m,h,c,p,d,0xFFFF,0xFFFF,0xFFFF);
        // Every value of both words in the hidden and the shown states.
        for(int w=0;w<65536;w++) {
            copyCase(3,0,0,0,0,w^0x5A5A,w,w^0xFFFF); copyCase(1,2900,0,0,0,w,w^0xFFFF,w);
            if((w&3)==0) { copyCase(1,0,100,0,0,w,w^0xFFFF,w); copyCase(1,0,100,0,1,w^0x5A5A,w,w^0xFFFF); }
        }
        Random r=new Random(0x0B5E);
        for(int k=0;k<20000;k++) copyCase(r.nextInt(65536),r.nextInt(65536),r.nextInt(65536),r.nextInt(65536),
            r.nextInt(256),r.nextInt(65536),r.nextInt(65536),r.nextInt(65536));
        emu.dispose();
        println("PASS: register copy - modes x counter boundaries x debounce, every word value, 20000 random states; only 23C2-23C7 written");
    }

    // 3. The real alarm-register builder, FE:A985 .. AC16, against stock.
    int[] builder(boolean stock,int[] objects,int mode,int hold,int cell,int pack,int dch) throws Exception {
        init();
        if(stock) restoreStock(emu,COPY,COPY+12);
        for(int i=0;i<objects.length;i++) byteAt(0x2410+i,objects[i]);
        state(mode,hold,cell,pack,objects[CELLUV-0x2410],dch);
        byteAt(0x2350,0);
        for(int a=0x2728;a<0x272E;a++) byteAt(a,0);
        emu.writeRegister("SP",FRAME); emu.writeRegister("PPAGE",0xFE);
        emu.writeRegister("PC",0x7FA985L);
        runTo(0xAC16,2000);
        check(reg("SP")==FRAME && reg("PPAGE")==0xFE,"builder: stack and page restored");
        int[] out={word(0x23C2),word(0x23C4),word(0x23C6)};
        emu.dispose();
        return out;
    }
    void builders() throws Exception {
        Random r=new Random(0xA1A2);
        int shown=0, hidden=0;
        for(int k=0;k<400;k++) {
            int[] objects=new int[11];
            for(int i=0;i<11;i++) objects[i]=k<256 && i==CELLUV-0x2410 ? k : r.nextInt(256);
            int mode=r.nextInt(4), hold=r.nextBoolean()?r.nextInt(3001):HOLD_WARN, cell=r.nextInt(101), pack=r.nextInt(101), dch=r.nextInt(31);
            int[] s=builder(true,objects,mode,hold,cell,pack,dch), p=builder(false,objects,mode,hold,cell,pack,dch);
            boolean show=soon(mode,hold,cell,pack,dch);
            check(p[0]==s[0],"1392 equals stock");
            int and=show?0xFFFF:0xFAFF;
            check(p[1]==(s[1]&and) && p[2]==(s[2]&and),String.format("1393/1394 stock %04X %04X patched %04X %04X",s[1],s[2],p[1],p[2]));
            if(show) shown++; else hidden++;
            cases++;
        }
        // The four hidden bits are exactly the undervoltage ones stock reports.
        int[] objects=new int[11];
        objects[CELLUV-0x2410]=0x03; objects[0x2419-0x2410]=0x03;      // cell and pack L1+L2
        int[] s=builder(true,objects,3,0,0,0,0), p=builder(false,objects,3,0,0,0,0);
        check((s[1]&0x0500)==0x0500 && (s[2]&0x0500)==0x0500,"stock reports cell UV and pack UV L2 in 1393 and 1394");
        check((p[1]&0x0500)==0 && (p[2]&0x0500)==0,"patched hides them");
        check(p[0]==s[0] && (s[0]&0x1400)==0x1400,"1392's L1 bits pass as in stock (the APS-500 ignores that byte)");
        objects=new int[11]; objects[0x2414-0x2410]=0x02; objects[0x2418-0x2410]=0x02; objects[0]=0x02;
        s=builder(true,objects,3,0,0,0,0); p=builder(false,objects,3,0,0,0,0);
        check(s[1]!=0 && p[1]==s[1] && p[2]==s[2],"overvoltage and overcurrent L2 still reported");
        println("PASS: real register builder vs stock - "+shown+" shown, "+hidden+" hidden; only UV bits 0500 differ");
    }

    // 4. The cap through its real site: FE:A96C .. A971.
    void prepareCap() throws Exception {
        init(); counters();
        emu.writeRegister("PPAGE",0xFE);
        trace=new Trace(emu,FRAME+16);
        trace.allowed=new HashSet<>(List.of(RAM+LIMIT,RAM+LIMIT+1)); trace.enforce=true;
    }
    int publish(int old,int target) throws Exception {
        word(LIMIT,old&0xFFFF);
        word(FRAME+2,target&0xFFFF);
        emu.writeRegister("SP",FRAME); emu.writeRegister("IY",0x2468);
        emu.writeRegister("PC",CAP);
        runTo(0xA971,80);
        check(reg("SP")==FRAME && reg("IY")==0x2468 && reg("PPAGE")==0xFE,"cap: stack, IY and page restored");
        check(word(FRAME+2)==(target&0xFFFF),"caller's local untouched");
        return word(LIMIT);
    }
    void capCase(int old,int t,int uv,int mode,int hold,int cell,int pack,int dch) throws Exception {
        state(mode,hold,cell,pack,uv,dch);
        int got=publish(old,t), want=published(old,t,uv,soon(mode,hold,cell,pack,dch))&0xFFFF;
        check(got==want,String.format("old=%d t=%d uv=%02X mode=%d hold=%d cell=%d pack=%d dch=%d got=%d want=%d",
            (short)old,(short)t,uv,mode,hold,cell,pack,dch,(short)got,(short)want));
        cases++;
    }
    void capCase(int old,int t,int uv,int mode,int hold,int cell,int pack) throws Exception { capCase(old,t,uv,mode,hold,cell,pack,0); }
    void caps() throws Exception {
        prepareCap();
        // Every alarm byte x every ladder output x a spread of previous values, hidden state.
        for(int uv=0;uv<256;uv++) for(int t:LADDER) for(int o=-10500;o<=0;o+=211) capCase(o,t,uv,3,0,0,0);
        // Shown state: always zero, whatever the request.
        for(int t:LADDER) for(int uv:new int[]{0,0x80,0xC0,0xFF}) for(int[] st:new int[][]{{1,2900,0,0,0},{1,3000,0,0,0},{3,0,60,0,0},{3,0,0,60,0},{2,0,100,100,0},{1,0,60,0,1},{1,0,0,100,30}})
            capCase(-6000,t,uv,st[0],st[1],st[2],st[3],st[4]);
        // Mode x counter boundaries x debounce.
        for(int m:MODES) for(int h:COUNTS) for(int c:COUNTS) for(int d:DEBOUNCE) capCase(-10500,-10500,0xC0,m,h,c,0,d);
        // Arbitrary requests, including impossible positive ones.
        Random r=new Random(0xCAB5);
        for(int k=0;k<20000;k++) capCase(r.nextInt(65536),r.nextInt(65536),r.nextInt(256),
            r.nextInt(4),r.nextInt(3200),r.nextInt(120),r.nextInt(120),r.nextInt(256));
        for(int t=0;t<65536;t+=3) capCase(-9000,t,r.nextInt(256),3,0,0,0);
        emu.dispose();
        println("PASS: cap - every alarm byte x ladder x previous value, the shown states, counter boundaries x debounce, random; only 0x23BE written");
    }

    // 5. The real current-selection block, pass by pass, at a low cell.
    int pass(int mv,int t,int uv,int mode,int hold,int cell,int pack) throws Exception {
        word(MODE,mode); word(0x25E4,mv); word(0x233C,t);
        word(0x23A6,0); word(0x23AC,0);
        for(int a:new int[]{0x2418,0x2414,0x2624,0x2415,0x2410}) byteAt(a,0);
        state(mode,hold,cell,pack,uv);
        emu.writeRegister("SP",FRAME); emu.writeRegister("PPAGE",0xFE);
        emu.writeRegister("PC",0x7FA8AAL);
        runTo(0xA971,2000);
        check(reg("SP")==FRAME && reg("PPAGE")==0xFE,"stack/page restored");
        return (short)word(LIMIT);
    }
    int until(int target,int uv,int mode,int hold,int cell,List<Integer> seen) throws Exception {
        for(int k=1;k<=1000;k++) {
            int v=pass(3000,250,uv,mode,hold,cell,0); seen.add(v);
            if(v==target) return k;
        }
        throw new AssertionError("did not reach "+target);
    }
    void scenarios() throws Exception {
        init();
        // No undervoltage: the selection is the slew256 image's (105 A, steady).
        word(LIMIT,-10500&0xFFFF);
        check(pass(3000,250,0x00,1,0,0,0)==-10500,"no alarm: 105 A as before");
        check(pass(3000,250,0x40,1,0,0,0)==-10500,"cell UV L1: 105 A as before");
        // L2: falls to 42 A through the slew, never faster.
        List<Integer> seen=new ArrayList<>();
        int n=until(-4200,0x80,1,0,0,seen);
        check(seen.get(0)==-9000,"L2 fall starts from the 90 A clamp");
        for(int k=1;k<seen.size();k++) check(seen.get(k)>seen.get(k-1),"L2 fall is monotonic");
        println("MEASURE cell UV L2, 105 -> 42 A: "+n+" passes ("+(n/10.0)+" s)");
        // L3: on to 21 A.
        seen.clear(); n=until(-2100,0xC0,1,0,100,seen);
        println("MEASURE cell UV L3, 42 -> 21 A: "+n+" passes ("+(n/10.0)+" s)");
        // Hold reaches the warning: zero at once.
        check(pass(3000,250,0xC0,1,2899,100,0)==-2100,"2899 ticks into the hold: still 21 A");
        check(pass(3000,250,0xC0,1,2900,100,0)==0,"2900 ticks into the hold: zero at once");
        // Charging, hold young, countdown saturated: zero as soon as net discharge is counted.
        word(LIMIT,-2100&0xFFFF); byteAt(DCH,1);
        check(pass(3000,250,0xC0,1,120,100,0)==-2100,"state() cleared the debounce: 21 A");
        byteAt(DCH,DCH_WARN); word(MODE,1); word(HOLD,120); word(CELL_COUNT,100); word(PACK_COUNT,0); byteAt(CELLUV,0xC0);
        emu.writeRegister("SP",FRAME); emu.writeRegister("PPAGE",0xFE); emu.writeRegister("PC",0x7FA8AAL);
        runTo(0xA971,2000);
        check(word(LIMIT)==0,"first tick of net discharge in charge mode at a saturated countdown: zero at once");
        // Not charging: zero 6 s into either countdown.
        word(LIMIT,-2100&0xFFFF);
        check(pass(3000,250,0xC0,3,0,59,0)==-2100,"5.9 s into the cell countdown: 21 A");
        check(pass(3000,250,0xC0,3,0,60,0)==0,"6 s into the cell countdown: zero");
        word(LIMIT,-2100&0xFFFF);
        check(pass(3000,250,0x00,3,0,0,60)==0,"6 s into the pack countdown: zero");
        // L3 clears: back up at once.
        word(LIMIT,0);
        check(pass(3000,250,0x80,1,0,0,0)==-4200,"L3 -> L2: 0 -> 42 A at once");
        check(pass(3000,250,0x00,1,0,0,0)==-10500,"cleared: 42 -> 105 A at once");
        // Existing gates still win: an L2 overvoltage alarm stops at once.
        word(LIMIT,-4200&0xFFFF); byteAt(0x2418,2);
        state(1,0,0,0,0x80);
        emu.writeRegister("SP",FRAME); emu.writeRegister("PPAGE",0xFE); emu.writeRegister("PC",0x7FA8AAL);
        runTo(0xA971,2000);
        check(word(LIMIT)==0,"pack OV L2 still stops at once");
        emu.dispose();
        cases+=12;
        println("PASS: real selection block - L2 42 A, L3 21 A through the slew; zero before a cut; rises immediate");
    }

    // 6. Stack, through both sites.
    void stack() throws Exception {
        init();
        state(1,0,0,0,0xC0); word(LIMIT,-10500&0xFFFF); word(FRAME+2,-10500&0xFFFF);
        emu.writeRegister("SP",FRAME); emu.writeRegister("PPAGE",0xFE); emu.writeRegister("PC",CAP); counters();
        runTo(0xA971,80);
        int cap=FRAME-minimumSP;
        emu.writeRegister("SP",FRAME); emu.writeRegister("PC",0x7FAC04L); counters();
        runTo(0xAC16,80);
        int copy=FRAME-minimumSP;
        println("MEASURE stack: cap site "+cap+" bytes (slew256 image: 2), copy site "+copy+" bytes (stock: 0)");
        check(cap==9,"cap: CALL 3 + two PSHD 4 + JSR 2");
        check(copy==5,"copy: CALL 3 + JSR 2 (the PSHB comes after the JSR returns)");
        emu.dispose();
    }

    // 8. Real main-loop passes in scheduler order (7F:804F-8067): mode detection,
    //    the two undervoltage evaluators, the post-adjust hold and the cut mask,
    //    relay output (PA1 opens there), then task_soc_or_balance's request
    //    selection and register build, which hold both patch sites. Inputs are
    //    the lowest cell, the pack voltage and the averaged current; every
    //    counter, alarm bit, mode and relay state is the firmware's own.
    static final long MODE_DETECT=0x7F2C20L, CHARGE_FLAGS=0x7F8C96L, CELL_EVAL=0x7F1346L, PACK_EVAL=0x7F15DBL,
        POST_ADJUST=0x7F17FDL, AGGREGATE=0x7F1A01L, RELAY=0x7F9164L, SELECT=0x7FA8AAL, BUILD=0x7FA985L;
    int passes;
    void current(double amps) throws Exception {        // 0x22A6: IEEE 754 single, discharge positive
        int bits=Float.floatToIntBits((float)amps); word(0x22A6,bits>>>16); word(0x22A8,bits&0xFFFF);
    }
    void call(long entry,int frame) throws Exception {  // as the scheduler's CALL: RTC returns to FE:4010
        emu.writeRegister("SP",FRAME-3-frame);
        emu.writeMemory(ram(FRAME-3),new byte[]{(byte)0xFE,0x40,0x10});
        emu.writeRegister("PPAGE",(int)(entry>>14)&0xFF); emu.writeRegister("PC",entry);
        runTo(0x4010,8000);
        check(reg("SP")==FRAME && reg("PPAGE")==0xFE,"stack and page restored after "+Long.toHexString(entry));
    }
    void block(long entry,int stop) throws Exception {  // a block of task_soc_or_balance, as in 5.
        emu.writeRegister("SP",FRAME); emu.writeRegister("PPAGE",0xFE); emu.writeRegister("PC",entry);
        runTo(stop,3000);
        check(reg("SP")==FRAME && reg("PPAGE")==0xFE,"stack and page restored after "+Long.toHexString(entry));
    }
    int limitAtCut; boolean warnedAtCut;
    void modeDetect() throws Exception { call(MODE_DETECT,0); call(CHARGE_FLAGS,0); }
    void alarms() throws Exception {
        call(CELL_EVAL,0); call(PACK_EVAL,0); call(POST_ADJUST,0);
        for(int i=0;i<5;i++) byteAt(FRAME-8+i,0);       // alarm_manager's cleared frame
        call(AGGREGATE,5);
    }
    void relays() throws Exception {                    // what the APS-500 can have read when PA1 opens
        boolean was=pa1(); call(RELAY,0);
        if(was && !pa1()) { limitAtCut=(short)word(LIMIT); warnedAtCut=warned(); }
    }
    void publish() throws Exception { block(SELECT,0xA971); block(BUILD,0xAC16); }
    void pass() throws Exception { modeDetect(); alarms(); relays(); publish(); passes++; }
    boolean pa1() throws Exception { return (emu.readMemory(g(0),1)[0]&2)!=0; }
    boolean pa2() throws Exception { return (emu.readMemory(g(0),1)[0]&4)!=0; }
    boolean warned() throws Exception { return word(LIMIT)==0 && ((word(0x23C4)|word(0x23C6))&0x0500)!=0; }
    void session(int cellmV,int packdV) throws Exception {
        // Stock settings: cvol_l1/l2/l3 3150/3100/3000 mV, tvol_l1/l2/l3 50.4/49.6/48.0 V.
        word(0x2658,3150); word(0x265A,3100); word(0x265C,3000);
        word(0x2640,504); word(0x2642,496); word(0x2644,480);
        word(0x25E7,cellmV); word(0x25E4,cellmV+10); word(0x25EE,packdV); word(0x233C,250);
        word(0x23A6,0); word(0x23AC,0); word(LIMIT,0);
        for(int i=0;i<11;i++) { byteAt(0x2410+i,0); word(0x2420+2*i,0x2410+i); }
        byteAt(CELLUV,0x08); byteAt(PACKUV,0x08);        // evaluators enabled, no alarm yet
        byteAt(0x25C3,1);                                // DC-DC lockout already recorded: no EEPROM write
        byteAt(0x241B,0); byteAt(0x2624,0); word(0x2BEC,3); word(MODE,2); word(HOLD,0);
        byteAt(DCH,0); byteAt(0x25B0,0); byteAt(0x2504,0); byteAt(0x250C,0);
        for(int a=0x24D2;a<0x2502;a+=2) word(a,0);
        // PA1 and PA2 closed and settled: relay_discharge_sm / relay_dcdc_sm state 3.
        byteAt(0x234C,0x0D); byteAt(0x2354,1); byteAt(0x2353,3);
        byteAt(0x234D,0x0D); byteAt(0x235D,1); byteAt(0x235C,3);
        byteAt(0x2350,0); byteAt(0x234E,0); byteAt(0x234F,0);
        emu.writeMemory(g(0),new byte[]{6});
        passes=0;
    }
    // Passes until PA1 opens: {first pass with the warning published, pass of the cut}. Once
    // published the warning must hold every pass up to the cut, and be what is published at it.
    int[] untilCut(int bound) throws Exception {
        int warn=0;
        for(int k=0;k<bound;k++) {
            pass();
            if(warn==0 && warned()) warn=passes;
            else if(warn>0) check(warned(),"warning held at pass "+(passes-warn)+" after it began");
            if(!pa1()) {
                check(warn>0 && limitAtCut==0 && warnedAtCut,"0 A and the UV bits were published when PA1 opened");
                return new int[]{warn,passes};
            }
        }
        throw new AssertionError("PA1 still closed after "+bound+" passes");
    }
    // A charge-mode L3 session: 21 A charging, L3 since the first second, hold running.
    void settle(int cellmV,int packdV,String name) throws Exception {
        session(cellmV,packdV); current(-21);
        boolean cell=cellmV<3000;
        // 105 A is published on the first pass, before L3 trips; the cap then falls through the slew.
        for(int k=0;k<160 || (short)word(LIMIT)!=(cell?L3_CAP:-10500);k++) {
            check(k<600,"the request settles"); pass();
            check(pa1(),"PA1 closed while charging"); check(!warned(),"no warning while charging");
        }
        check(word(MODE)==1,"charge mode after 3 s of 21 A");
        check(cell ? (byteAt(CELLUV)&0xC4)==0xC4 && word(CELL_COUNT)==100 && (byteAt(PACKUV)&0xC7)==0
                   : (byteAt(PACKUV)&0xC4)==0xC4 && word(PACK_COUNT)==100 && (byteAt(CELLUV)&0xC7)==0,
              name+": only the intended L3, level 3, countdown saturated");
        check(word(HOLD)>0 && word(HOLD)<HOLD_WARN,"hold running: "+word(HOLD));
        check((byteAt(0x241B)&0x10)==0,"the hold is clearing the PA1 cut bit");
        check(!pa2(),"PA2 shed after 5 s at L3");
        check((short)word(LIMIT)==(cell?L3_CAP:-10500),"request before the load: "+(short)word(LIMIT));
    }
    // Then a load above the alternator's output.
    int transition(int cellmV,int packdV,String name) throws Exception {
        settle(cellmV,packdV,name);
        int start=passes, hold=word(HOLD);
        current(5);
        int[] r=untilCut(60);
        int lead=r[0]==0 ? -1 : r[1]-r[0];
        println(String.format("MEASURE %s, charge-mode L3 session (hold %d) then net discharge: warning at discharge pass %d, PA1 open at pass %d, lead %d passes (%.1f s)",
            name,hold,r[0]-start,r[1]-start,lead,lead/10.0));
        check(r[1]-start==31,"stock leaves charge mode and opens PA1 on the 31st discharge pass");
        check(word(MODE)==3 && word(HOLD)==0 && (byteAt(0x241B)&0x10)!=0,"cut by the L3 bit once the hold ended");
        check(r[0]-start==DCH_WARN && lead==31-DCH_WARN,"warning on the first discharge pass, 3.0 s before the cut");
        for(int k=0;k<5;k++) { pass(); check(!pa1() && warned(),"stays open and warned"); }
        cases++;
        return lead;
    }
    void passes() throws Exception {
        init();
        transition(2950,520,"cell L3");
        transition(3300,470,"pack L3");
        // The hold runs out while charging: the warning 100 passes before the cut.
        // The hold is a plain counter, so it is advanced to 2800 after the session settles.
        session(2950,520); current(-21);
        for(int k=0;k<160;k++) pass();
        word(HOLD,2800);
        int start=passes;
        int[] r=untilCut(300);
        println(String.format("MEASURE cell L3, hold expiry while charging: warning at pass %d, PA1 open at pass %d (hold 2800 at pass 0), lead %d passes (%.1f s)",
            r[0]-start,r[1]-start,r[1]-r[0],(r[1]-r[0])/10.0));
        check(r[0]-start==100 && r[1]-start==201 && word(MODE)==1,"2900 -> warning; the pass after 3000 -> cut; still charging");
        // Not charging: the warning 40 passes before the cut.
        session(2950,520); current(5);
        r=untilCut(200);
        println(String.format("MEASURE cell L3, discharging: L3 at pass 10, warning at pass %d, PA1 open at pass %d, lead %d passes (%.1f s)",
            r[0],r[1],r[1]-r[0],(r[1]-r[0])/10.0));
        check(r[0]==70 && r[1]==111 && word(MODE)==3,"countdown 60 -> warning; the pass after 100 -> cut");
        session(3300,470); current(5);
        r=untilCut(200);
        check(r[0]==70 && r[1]==111,"pack L3, discharging: the same");
        // A short net discharge while charging: the warning clears with the debounce.
        session(2950,520); current(-21);
        for(int k=0;k<160;k++) pass();
        current(5); for(int k=0;k<10;k++) { pass(); check(warned() && pa1(),"warned, still closed"); }
        current(-21); pass();
        check(pa1() && !warned() && byteAt(DCH)==0 && word(MODE)==1,"a 1 s discharge: charging resumes, PA1 closed");
        check((short)word(LIMIT)==L3_CAP,"21 A again at once");
        emu.dispose();
        cases+=4;
        println("PASS: real passes in scheduler order - mode 1 -> 3 under load warns 3.0 s before PA1 opens, cell and pack; hold expiry 10.1 s; countdown 4.1 s");
    }

    // 9. The CAN4 0x100 work-mode byte through the real handler (7F4FDB, run to its
    //    epilogue at 55CE on the ISR's frame), delivered between passes and between
    //    the steps of a pass. 500C now stores the byte only while no L3 countdown runs.
    int canDepth;
    void can100(int d2,int mode,int ref) throws Exception {
        word(0x261E,0x0280);
        emu.writeMemory(g(0x2A4),new byte[]{0,0,(byte)d2,(byte)mode,0,0,(byte)(ref>>8),(byte)ref});
        emu.writeRegister("SP",FRAME-0x17); emu.writeRegister("PPAGE",0xFE); emu.writeRegister("PC",0x7F4FDBL);
        counters(); runTo(0x55CE,400);
        canDepth=Math.max(canDepth,FRAME-0x17-minimumSP);
        check(reg("SP")==FRAME-0x17 && reg("PPAGE")==0xFE,"handler: frame and page kept");
        check(byteAt(0x2518)==(d2>>7) && byteAt(0x2519)==(d2&0x7F) && word(0x251C)==ref,"balance enable, threshold and reference stored");
        check(word(0x261E)==0x0280 && word(0x2BEC)==3,"register pointer and state untouched");
    }
    void canMode() throws Exception {
        init();
        for(int[] c:new int[][]{{2950,520},{3300,470}}) {
            String name=c[0]<3000?"cell L3":"pack L3";
            settle(c[0],c[1],name);
            int hold=word(HOLD), limit=(short)word(LIMIT);
            // Between passes, before mode detection.
            can100(0x94,3,3300);
            check(word(MODE)==1,name+": mode 3 frame ignored while the countdown runs");
            pass(); check(pa1() && !warned() && word(MODE)==1,"charging on");
            // Inside a pass, between mode detection and the alarms.
            modeDetect(); can100(0x94,3,3300); check(word(MODE)==1,"ignored mid-pass");
            alarms(); relays(); check(pa1(),"PA1 closed"); publish(); passes++;
            check(!warned() && word(MODE)==1,"charging on");
            // Repeated, every pass, with every other mode value.
            for(int k=0;k<40;k++) { can100(k%2==0?0x94:0x14,new int[]{0,2,3,0xFF,1}[k%5],3300); pass();
                check(pa1() && !warned() && word(MODE)==1,"repeated frames ignored"); }
            check(word(HOLD)>hold && (short)word(LIMIT)==limit,name+": the hold and the request carry on");
            cases+=43;
        }
        // Below L3 the byte is stored as in stock; the current-based detection then takes over.
        session(3050,520); current(-21);
        for(int k=0;k<60 || (short)word(LIMIT)!=L2_CAP;k++) { check(k<400,"the 42 A cap settles"); pass(); }
        check(word(MODE)==1 && (byteAt(CELLUV)&0xC7)==0x83 && word(CELL_COUNT)==0 && (short)word(LIMIT)==L2_CAP,"cell L2 session, no countdown");
        can100(0x94,3,3300);
        check(word(MODE)==3,"at L2 a mode 3 frame is stored as in stock");
        pass(); check(pa1() && !warned(),"no cut below L3");
        for(int k=0;k<31;k++) pass();
        check(word(MODE)==1 && pa1(),"3 s of 21 A puts it back in charge mode");
        // Discharging at L3: a mode 1 frame is ignored too, and the countdown warning stands.
        session(2950,520); current(5);
        for(int k=0;k<40;k++) pass();
        check(word(MODE)==3 && word(CELL_COUNT)==30,"discharge-mode L3 session");
        can100(0x94,1,3300);
        check(word(MODE)==3,"mode 1 frame ignored while the countdown runs");
        int[] r=untilCut(200);
        check(r[0]==70 && r[1]==111,"warning at countdown 60, cut after 100, as without the frame");
        println("MEASURE CAN4 0x100 handler: "+canDepth+" bytes of stack below the ISR frame (stock: 0; the CALL adds 3)");
        emu.dispose();
        cases+=4;
        println("PASS: CAN4 0x100 - mode byte ignored while an L3 countdown runs (cell, pack; before and inside a pass; repeated), stored below L3; balancing fields always stored");
    }

    // 7. Negative controls: each breaks one property and must be caught.
    void patchByte(long at,int expect,int value) throws Exception {
        check((emu.readMemory(g(at),1)[0]&255)==expect,"control targets the intended byte "+Long.toHexString(at));
        emu.writeMemory(g(at),new byte[]{(byte)value});
    }
    void controls() throws Exception {
        rejects("hide mask FA -> FF: undervoltage bits reach the APS-500",()->{
            prepareCopy(); patchByte(0x7F37A8L,0xFA,0xFF); copyCase(3,0,0,0,0,0,0x0500,0x0500); });
        rejects("hold warning never fires (BHS -> BRN)",()->{
            prepareCopy(); patchByte(0x7F378EL,0x24,0x21); copyCase(1,2950,0,0,0,0,0x0500,0x0500); });
        rejects("pack countdown ignored (second BHS -> BRN)",()->{
            prepareCopy(); patchByte(0x7F37A5L,0x24,0x21); copyCase(3,0,0,80,0,0,0x0500,0x0500); });
        rejects("discharge debounce ignored while charging (BLO -> BRA)",()->{
            prepareCopy(); patchByte(0x7F3795L,0x25,0x20); copyCase(1,120,100,0,1,0,0x0500,0x0500); });
        rejects("debounce threshold off by one (CMPA #1 -> #2)",()->{
            prepareCopy(); patchByte(0x7F3794L,0x01,0x02); copyCase(1,120,100,0,1,0,0x0500,0x0500); });
        rejects("copy splice reverted to stock",()->{
            prepareCopy(); restoreStock(emu,COPY,COPY+12); copyCase(3,0,0,0,0,0,0x0500,0x0500); });
        rejects("no zero before a cut (INCB -> NOP)",()->{
            prepareCap(); patchByte(0x7F37BFL,0x52,0xA7); capCase(-2100,-2100,0xC0,1,2950,100,0); });
        rejects("L3 cap skipped (BRCLR #40 -> #00 always branches)",()->{
            prepareCap(); patchByte(0x7F37D4L,0x40,0x00); capCase(-4200,-10500,0xC0,1,0,100,0); });
        rejects("L2 cap skipped (BRCLR #80 -> #00 always branches)",()->{
            prepareCap(); patchByte(0x7F37C7L,0x80,0x00); capCase(-10500,-10500,0x80,1,0,0,0); });
        rejects("transition: debounce ignored, so PA1 opens before the warning (BLO -> BRA)",()->{
            init(); patchByte(0x7F3795L,0x25,0x20); transition(2950,520,"control"); emu.dispose(); });
        rejects("CAN mode splice reverted to stock: a mode 3 frame opens PA1 before the warning",()->{
            init(); restoreStock(emu,MODE_SPLICE,MODE_SPLICE+6); settle(2950,520,"control");
            can100(0x94,3,3300); int[] r=untilCut(5); check(r[0]>0 && r[1]-r[0]>=30,"warned 3 s before the cut"); emu.dispose(); });
        rejects("cell countdown ignored by the mode gate (first BNE -> BRN)",()->{
            init(); patchByte(0x7F37F0L,0x26,0x21); settle(2950,520,"control"); can100(0x94,3,3300); check(word(MODE)==1,"ignored"); emu.dispose(); });
        rejects("pack countdown ignored by the mode gate (second BNE -> BRN)",()->{
            init(); patchByte(0x7F37F5L,0x26,0x21); settle(3300,470,"control"); can100(0x94,3,3300); check(word(MODE)==1,"ignored"); emu.dispose(); });
    }

    @Override public void run() throws Exception {
        loadImage();
        disassemble(g(CODE)); disassemble(g(0x7F37AAL)); disassemble(g(0x7F37BCL)); disassemble(g(0x7F37EDL));
        for(long a=CODE;a<CODE+0x7E;) {
            var ins=currentProgram.getListing().getInstructionAt(g(a));
            if(ins==null) { a++; continue; }
            println(String.format("%06X %s",a,ins)); a+=ins.getLength();
        }
        init(); identity(); emu.dispose();
        copies();
        builders();
        caps();
        scenarios();
        stack();
        passes();
        canMode();
        controls();
        println("UV CHARGE PASSED: "+cases+" cases, "+assertions+" assertions");
    }
}
