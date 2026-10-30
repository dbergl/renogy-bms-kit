//Test helper: keep the project open (and locked) for N seconds
//@category Test
import ghidra.app.script.GhidraScript;

public class HoldProject extends GhidraScript {
    @Override
    public void run() throws Exception {
        println("HOLDING");
        Thread.sleep(Long.parseLong(getScriptArgs()[0]) * 1000);
    }
}
