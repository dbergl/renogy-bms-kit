// Differential test of the reclaimed CAN4 ID decoder. No hardware access.
// Arguments: patched global S19, stock global S19.
//@category Test
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.*;
import java.util.*;

public class DecoderHeaterTest extends EmuHeaterTest {
    EmulatorHelper original, patched;
    int cases;
    final int[] ids={0x11,0x100,0x20F,0x300,0x310,0x311,0x312,0x313,
        0x410,0x411,0x412,0x413,0x414,0x415,0x416,0x417,0x418,0x419,0x41A,
        0x500,0x999,0x08F62DE2};
    final int[] entries={0x5070,0x4FDB,0x50C5,0x50F2,0x515D,0x5193,0x520B,0x5241,
        0x5277,0x52C0,0x5309,0x5352,0x539B,0x53E4,0x542D,0x5476,0x54B9,0x54FC,0x553F,
        0x508A,0x5581,0x5056};

    void reset(EmulatorHelper helper, byte[] raw) throws Exception {
        emu=helper;
        emu.writeRegister("PC",0x7F4D47L);
        emu.writeRegister("SP",0x2200);
        emu.writeRegister("CCR",0xD0); // ISR masked; prologue must establish decode inputs
        emu.writeRegister("D",0xABCD);
        emu.writeRegister("IX",0x9876);
        emu.writeRegister("IY",0x5678);
        emu.writeRegister("PPAGE",0xFC);
        byte[] stack=new byte[128]; Arrays.fill(stack,(byte)0xA5);
        emu.writeMemory(ram(0x21C0),stack);
        word(0x261E,0x0280); word(0x2BEC,3);
        emu.writeMemory(g(0x2A0),raw);
    }

    long decode(EmulatorHelper helper, byte[] raw) throws Exception {
        reset(helper,raw); until(0x4E39);
        check(reg("SP")==0x21E9,"decoder stack balance");
        check((reg("CCR")&16)!=0 && reg("PPAGE")==0xFC,"decoder mask/page");
        check(Arrays.equals(emu.readMemory(g(0x2A0),4),raw),"ID registers are read-only");
        return ((long)word(0x21E9)<<16)|word(0x21EB);
    }

    void compare(byte[] raw) throws Exception {
        long before=decode(original,raw), after=decode(patched,raw);
        int a=raw[0]&255,b=raw[1]&255,c=raw[2]&255,d=raw[3]&255;
        long expected=(b&8)==0 ? (a<<3)|(b>>>5)
            : ((long)a<<21)|((long)(b&0xE0)<<13)|((long)(b&7)<<15)|(c<<7)|(d>>>1);
        check(before==expected && after==before,"decode mismatch "+HexFormat.of().formatHex(raw)
            +" stock="+Long.toHexString(before)+" patched="+Long.toHexString(after));
        cases++;
    }

    byte[] encoded(int id,boolean extended,boolean remote) {
        return extended ? new byte[]{(byte)(id>>>21),(byte)(((id>>>13)&0xE0)|0x18|((id>>>15)&7)),
            (byte)(id>>>7),(byte)((id<<1)|(remote?1:0))}
            : new byte[]{(byte)(id>>>3),(byte)((id<<5)|(remote?16:0)),(byte)0xA5,(byte)0x5A};
    }

    void dispatch() throws Exception {
        int count=0;
        for(int state:new int[]{0,1,2,3,4,5}) for(boolean ext:new boolean[]{false,true}) {
            for(int i=0;i<ids.length;i++) {
                if(!ext && ids[i]>0x7FF) continue;
                for(EmulatorHelper helper:new EmulatorHelper[]{original,patched}) {
                    reset(helper,encoded(ids[i],ext,false)); word(0x2BEC,state);
                    int expected=state==3 || state==4 ? entries[i] : 0x55CE;
                    int steps=0;
                    while((pc()&65535)!=expected && steps++<2000) {
                        // Any different handler reached is a routing error, not a timeout.
                        int here=(int)(pc()&65535);
                        for(int entry:entries) check(here!=entry,"unexpected dispatcher target");
                        check(here!=0x55CE,"unexpected rejected command");
                        step();
                    }
                    check((pc()&65535)==expected,"dispatcher target reached");
                    check(reg("SP")==0x21E9,"dispatcher stack balance");
                    check((reg("CCR")&16)!=0,"dispatcher interrupt mask");
                }
                count++;
            }
        }
        // Near-misses must not alias the two extended command IDs or settings requests.
        for(int id:new int[]{0,0x7FF,0x998,0x99A,0x10999,0x1FFF0999,0x08F62DE3,0x501,0x1FFFFFFF}) {
            for(EmulatorHelper helper:new EmulatorHelper[]{original,patched}) {
                reset(helper,encoded(id,true,false)); until(0x55CE);
                check(reg("SP")==0x21E9,"unmatched frame stack");
            }
            count++;
        }
        println("PASS: stock/patched dispatch to all 22 command handlers, standard/extended and states 0..5; cases="+count);
    }

    @Override public void run() throws Exception {
        loadImage();
        setup(0x7F4D47L,0xFC); patched=emu;
        setup(0x7F4D47L,0xFC); original=emu;
        // Restore original instructions in a separate emulator, not in the test image.
        int restored=0;
        for(String line:Files.readAllLines(Path.of(getScriptArgs()[1]))) {
            if(!line.startsWith("S3")) continue;
            long address=Long.parseLong(line.substring(4,12),16);
            byte[] data=HexFormat.of().parseHex(line.substring(12,line.length()-2));
            for(int i=0;i<data.length;i++) if(address+i>=0x7F4D78L && address+i<0x7F4E39L) {
                original.writeMemory(g(address+i),new byte[]{data[i]}); restored++;
            }
        }
        check(restored==193,"all original decoder bytes loaded");
        for(int id=0;id<2048;id++) for(boolean rtr:new boolean[]{false,true}) compare(encoded(id,false,rtr));
        println("PASS: every standard ID, data and remote frames (4096 differential cases)");
        // Exercise every raw IDR1 value with all-zero/all-one surrounding fields,
        // including unused bits, IDE/SRR combinations and remote frames.
        for(int b=0;b<256;b++) for(int fill:new int[]{0,255})
            compare(new byte[]{(byte)fill,(byte)b,(byte)fill,(byte)fill});
        for(int bit=0;bit<29;bit++) for(boolean rtr:new boolean[]{false,true}) {
            compare(encoded(1<<bit,true,rtr)); compare(encoded(0x1FFFFFFF^(1<<bit),true,rtr));
        }
        Random random=new Random(0xC0DEC0DEL);
        for(int i=0;i<4096;i++) {
            byte[] raw=new byte[4]; random.nextBytes(raw); raw[1]|=8; compare(raw);
        }
        println("PASS: original machine code versus compact decoder and independent ID formula; total cases="+cases);
        dispatch(); original.dispose(); patched.dispose();
        println("CAN DECODER DIFFERENTIAL ACCEPTANCE PASSED");
    }
}
