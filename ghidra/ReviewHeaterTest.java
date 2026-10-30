// In-place regression acceptance: expects all four previously reported defects FIXED.
//@category Test
public class ReviewHeaterTest extends EmuHeaterTest {
    void validation() throws Exception {
        for(int dlc=0;dlc<16;dlc++) if(dlc!=8) {
            init(); command(77); can1Command(0xAA55,dlc,false,0);
            check(word(0x2254)==77 && word(0x2250)==0xAA55,"reject DLC "+dlc);
            relay(true,"invalid DLC unchanged"); emu.dispose();
        }
        for(int magic:new int[]{0,0xAA54,0xAB55,0x55AA,0xFFFF}) {
            init(); command(77); can1Command(magic,8,false,0);
            check(word(0x2254)==77,"reject magic"); relay(true,"bad magic"); emu.dispose();
        }
        init(); command(77); can1Command(0xAA55,8,true,0);
        check(word(0x2254)==77,"reject RTR"); relay(true,"RTR unchanged"); emu.dispose();
        init(); can1Frame(0x199,false,8,false,payload(0xAA55,77));
        check(word(0x2250)==0,"standard alias rejected"); relay(false,"standard alias"); emu.dispose();
        for(int state:new int[]{0,1,2,3,4,5}) {
            init(); word(0x2BEC,state); command(3);
            check(word(0x2254)==(state==3 || state==4 ? 3:0),"state reception");
            relay(state==3,"state manual gate"); emu.dispose();
        }
        println("PASS: DLC 0..15, wrong magic, RTR, standard alias and system-state validation, all on CAN1");
    }
    void expiry() throws Exception {
        for(int state:new int[]{3,4}) for(int mode:new int[]{0,1,2,3}) for(int stock=0;stock<2;stock++) {
            init(); byteAt(0x2350,stock); command(3); word(0x2BEC,state); word(0x2BE6,mode);
            byteAt(0x234D,0); // DCDC not ready throughout
            for(int remaining=2;remaining>=0;remaining--) {
                tick(); check(word(0x2254)==remaining,"unconditional lease expiry");
                relay(stock!=0 || (state==3 && remaining>0),"unconditional output reconciliation");
            }
            check(word(0x2250)==0,"expired session disarmed");
            byteAt(0x2350,0); output(); relay(false,"no stale manual restart"); emu.dispose();
        }
        init(); byteAt(0x2350,1); command(300);
        for(int i=0;i<600;i++) tick();
        check(word(0x2254)==0,"30s lease expires during 60s automatic heating");
        byteAt(0x2350,0); output(); relay(false,"automatic ends without stale lease"); emu.dispose();
        println("PASS: expiry during automatic heating, work mode 0, state 4 and DCDC not ready");
    }
    void temperature() throws Exception {
        init(); command(100); word(0x233E,401);
        for(int i=1;i<=30;i++) {
            tick(); check(byteAt(0x24E4)==i,"one debounce count per PIT tick");
            output(); output(); check(byteAt(0x24E4)==i,"main output must not double-age debounce");
            relay(true,"debounce pending");
        }
        tick(); relay(false,"overtemp trips with DCDC gate skipped");
        word(0x233E,400); tick(); relay(false,"inhibit retained at 40C");
        word(0x233E,391); tick(); relay(false,"inhibit retained at 39.1C");
        word(0x233E,390); tick(); relay(true,"inhibit releases at 39C");
        check(byteAt(0x24E4)==0,"debounce cleared"); emu.dispose();
        println("PASS: stock temperature debounce/hysteresis; no double counting during manual session");
    }
    void scheduler() throws Exception {
        init(); command(3);
        for(int i=1;i<=100;i++) {
            int sp=reg("SP"), ccr=reg("CCR");
            emu.writeRegister("PC",0x7F4AABL); emu.writeRegister("CCR",ccr|16);
            until(0x4B7D); // stop before unrelated stock call in PIT epilogue
            check(word(0x2254)==(i<100?3:2),"100 ms PIT divider at "+i);
            check(reg("SP")==sp,"PIT hook stack balanced");
            emu.writeRegister("CCR",ccr);
        }
        check((byteAt(0x232C)&4)!=0,"original task flag replayed"); emu.dispose();
        println("PASS: full PIT divider invokes expiry once per 100 interrupts");
    }
    void race(boolean timer, boolean portWriter) throws Exception {
        int boundaries=0;
        for(int inject=0;inject<150;inject++) {
            init(); command(3000);
            int savedCCR=reg("CCR");
            int stop;
            if(timer) { emu.writeRegister("CCR",savedCCR|16); emu.writeRegister("PC",0x7F4AE7L); stop=0x4AEB; }
            else {
                emu.writeRegister("SP",0x21FD);
                emu.writeMemory(ram(0x21FD),new byte[]{(byte)0xFE,0x40,0x10});
                emu.writeRegister("PC",portWriter?0x7F8D33L:0x7F9139L); stop=0x4010;
            }
            int n=0; boolean pending=false,delivered=false;
            while((pc()&65535)!=stop && n<200) {
                if(n==inject) pending=true;
                if(pending && (reg("CCR")&16)==0) { command(0); pending=false; delivered=true; }
                step(); n++;
            }
            check(n<200,"race execution bound");
            if(inject>=n) { emu.dispose(); break; }
            // Hardware dispatches a pending CAN interrupt after PIT RTI unmasks it.
            if(timer) emu.writeRegister("CCR",savedCCR);
            if(pending) { check((reg("CCR")&16)==0,"eventually interruptible"); command(0); delivered=true; }
            check(delivered,"OFF serviced");
            check(word(0x2254)==0 && word(0x2250)==0,"OFF not resurrected");
            relay(false,"OFF interleaving boundary "+inject);
            tick(); output(); relay(false,"OFF remains off");
            emu.dispose(); boundaries++;
        }
        check(boundaries>5,"meaningful interleaving coverage");
        println("PASS: OFF at every instruction boundary of "+(timer?"PIT tick":portWriter?"PA3 writer":"main heater output")+", boundaries="+boundaries);
    }
    @Override public void run() throws Exception {
        loadImage(); basic(); validation(); expiry(); temperature(); scheduler();
        race(true,false); race(false,false); race(false,true);
        println("IN-PLACE ALL FOUR FIXES: ACCEPTANCE PASSED");
    }
}
