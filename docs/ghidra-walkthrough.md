# Ghidra walkthrough

From a fresh computer to an annotated Ghidra program of your own stock V137.4.5 firmware, then a short tour
of finding your way around it and making a patch. No Ghidra experience is assumed. Windows paths are shown;
macOS and Linux work the same way.

## 1. Install what you need

| What | Where | Notes |
|---|---|---|
| Java (JDK) 21 or newer | adoptium.net (Temurin) | Ghidra 12.1 needs JDK 21+. Install the full JDK, not just a JRE. |
| Ghidra 12.1.4 | github.com/NationalSecurityAgency/ghidra/releases | Unzip anywhere, e.g. `C:\ghidra_12.1.4_PUBLIC`. Other 12.x versions probably work; the kit was tested on 12.1.4 and warns otherwise. |
| Python 3.10+ | python.org | Tick "Add python.exe to PATH" in the Windows installer. |
| This kit | `git clone`, or download the ZIP from GitHub | Then `pip install -r requirements.txt` in the kit folder. |

Start Ghidra once with `ghidraRun.bat` (`ghidraRun` on macOS/Linux) to check that Java is found.

## 2. Get and check your stock firmware

Unzip Renogy's update package. The firmware is `2. Firmware\V137.4.5[20240719a].s19`. From the kit folder:

```
python tools\verify_stock.py "C:\Renogy\RBT210LFP51S-12 Update Instructions 137.4.5\2. Firmware\V137.4.5[20240719a].s19"
```

It must print `stock V137.4.5`. If it says the line endings were changed, re-extract the file with Windows'
own "Extract All". Some unzip tools and git convert CRLF to LF.

## 3a. One command (recommended)

Close Ghidra first, then run:

```
python tools\ghidra_setup.py --ghidra C:\ghidra_12.1.4_PUBLIC --stock "<path to the .s19>" --project C:\ghidra-projects
```

It takes about a minute. At the end it prints `MARKUP functions=77 labels=85 structs=2 uses=2 errors=0`. Open
Ghidra, choose **File → Open Project**, pick `C:\ghidra-projects\renogy_bms.gpr`, and double-click
`stock_global.s19`. When Ghidra asks to analyze, say **No**: it is already analyzed.

Re-running the command replaces the program, so you can always get back to a clean, annotated copy.

## 3b. The same steps by hand

1. **Convert to the global layout.** Ghidra's HCS12X loader wants one flat address space, while Renogy's file is
   banked:

   ```
   python tools\to_global.py "<path to the .s19>" C:\ghidra-projects\stock_global.s19
   ```

   [The firmware map](firmware-map.md) explains the mapping: page FE local `0x8000` becomes global `0x7F8000`, and
   RAM `0x2350` appears as `0x0FE350`.
2. **Create a project:** File → New Project → Non-Shared → choose a folder and a name.
3. **Import:** File → Import File → `stock_global.s19`.
   - Format: **Motorola Hex**.
   - Language: click the `…` button and choose **HCS-12X, 24-bit, big-endian, default** (`HCS-12X:BE:24:default`).
4. When the import finishes, double-click the program to open it. When it asks **"Analyze now?", answer No.**
   The paging setup must run first.
5. **Add the kit's scripts.**
   1. Open Window → Script Manager.
   2. Click the "Manage Script Directories" icon (a bulleted list).
   3. Add the kit's `ghidra` folder.
   4. Close the dialog, and type `ApplyMarkup` in the Script Manager's filter box to check that the scripts appear.
6. **Run `SetupHCS12.java`** (double-click it in the Script Manager). It sets the PPAGE register for each flash
   page and creates the reset entry and the interrupt vectors.
7. **Analyze:** Analysis → Auto Analyze → Analyze, with the default options. Wait for the progress bar at the
   bottom right to finish.
8. **Run `FixupDisasm.java`.** It disassembles call targets the analyzer left as stubs, recovers the compiler's
   switch tables (`JSR 0x4A70`), and splits functions the analyzer merged.
9. **Run `ApplyMarkup.java`** and choose the kit's `markup` folder when it asks. The console should end with
   `MARKUP … errors=0`.
10. Save (File → Save).

## 4. Finding your way around

| To… | Do this |
|---|---|
| Jump to a function or variable | Press **G** and type a name, e.g. `heater_control` or `g_relay_cut_mask`, or an address such as `7F35FE`. |
| See every named thing | Window → Symbol Tree: Functions, Labels. Everything the kit named is listed in [the function reference](function-reference.md). |
| Read a function as C | The Decompiler window (Window → Decompiler) follows the Listing cursor. Names and comments from the markup show in both. |
| Read what a function does | The plate comment above the function: the kit's summary, then inputs, outputs and RAM touched. |
| Find who calls a function, or who uses a variable | Right-click its name → References → Show References to. |
| See the settings layout | Press G, type `0FE626`: the `bms_settings` struct, one field per setting, frames 0x410–0x41A. The types are under Data Type Manager → `renogy_bms`. |
| See the alarm objects | G → `0FE410`: an array of 11 `alarm_object` bytes. Bits 0–2 L1/L2/L3, bit 3 reset, bits 4/5 relay-cut requests, bits 6–7 level. |
| Follow a CAN command | Start at `ISR_CAN4_rx_commands` (CAN4) or `ISR_CAN1_rx` (CAN1, the RV-C bus); see [the CAN protocol](can-protocol.md). |

Things that trip people up:

- **Banked vs. global addresses.** Code calls `JSR 0x4E27` (a local address). In Ghidra that is global
  `0x7F4E27`. RAM operands like `0x2350` refer to `0x0FE350`.
- **The decompiler mis-reads some HCS12X instructions.** Extended `BRSET`/`BRCLR` conditions and the long
  signed branches can come out inverted. When a condition matters, read the instruction in the Listing.
  [Emulator testing](emulator-testing.md) has the details.
- **Calls through the switch helper at `0x4A70`** are switch statements; `FixupDisasm.java` labels their cases.

## 5. Making a patch

Ghidra is for understanding the code. Build the image you flash with `tools/make_patch.py`, which is how every
recipe is built:

1. **Find a place.**
   - Free flash, or bytes your change makes unused. [The firmware map](firmware-map.md) lists free regions and
     their risk.
   - A splice point: an existing instruction you replace with a `JSR` to your code, plus `NOP`s (`A7`) to keep the
     length.
2. **Write the code** in `make_patch.py`'s `Assembler`: `emit('hex bytes')` for each instruction, `label('name')`,
   `branch(opcode, 'label')`, `jsr('label')`. Opcodes stay explicit, so each byte can be checked against the
   Listing. Ghidra's Listing (right-click → Patch Instruction) is a good way to work out the bytes for an
   instruction: try it in a scratch copy of the program, then read off the bytes.
3. **Check the stock bytes you replace.** `make_patch.py` asserts the expected stock bytes at every splice and
   refuses an image other than the exact stock file. Do the same for yours.
4. **Build, audit, emulate.**
   - Build with `python tools/make_patch.py <stock.s19> <out.s19> [flags]`.
   - Audit with `python tools/audit_image.py <stock.s19> <out.s19>`. Your bytes show as `unreviewed`.
   - Emulate by extending a harness in `ghidra/`; see [emulator testing](emulator-testing.md).
5. **To look at your patched image in Ghidra**, convert it with `to_global.py` and import it the same way, or use
   `ghidra_setup.py` with a different `--name`. (`ghidra_setup.py` only accepts the stock file, so for a patched
   image follow steps 3b.2–3b.9.)
6. **Flash it:** [flashing and recovery](flashing-and-recovery.md).
