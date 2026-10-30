# Emulator testing

This page covers how to run the kit's Ghidra emulator tests with
[`tools/run_emulator_tests.py`](../tools/run_emulator_tests.py), what each test in
[`ghidra/tests.json`](../ghidra/tests.json) establishes about a recipe image, how to write a test for your
own patch by extending [`ghidra/VerifiedHeaterHarness.java`](../ghidra/VerifiedHeaterHarness.java), and the
places where Ghidra's emulator gets this CPU wrong and how the harness corrects them. The tests execute the
real firmware bytes instruction by instruction in Ghidra's p-code emulator. Nothing is flashed and no CAN
frame is sent.

## What the tests are and are not

| They establish | They do not establish |
|---|---|
| The image is exactly its base image plus the declared bytes (each test starts with an identity check) | That the bootloader accepts the image |
| Real firmware routines, through their real call sites, produce the results a written model predicts, over exhaustive or large swept input sets | Cycle timing, interrupt latency or worst-case stack use on silicon |
| Interrupts injected at every instruction boundary of a critical routine do not lose or corrupt state | MSCAN arbitration or bus timing; peripheral registers are RAM fixtures |
| Writes stay inside an allow-list; pages, stack and caller CCR are restored | Physical relay, heater or sensor behaviour |
| Each check catches the defect it targets: negative controls mutate the image and must fail | |

## Running them

Requirements: Ghidra 12.1.4 (another version prints a warning; run
[`ghidra/EmulatorQuirkProbe.java`](../ghidra/EmulatorQuirkProbe.java) under it first, see below), Python 3,
and your stock `V137.4.5[20240719a].s19`, checked by [`tools/verify_stock.py`](../tools/verify_stock.py).

```sh
python tools/run_emulator_tests.py --ghidra <Ghidra folder> --stock <stock.s19> --work <folder> --recipe uvcharge
python tools/run_emulator_tests.py --ghidra <Ghidra folder> --stock <stock.s19> --work <folder> --all
python tools/run_emulator_tests.py --ghidra <Ghidra folder> --stock <stock.s19> --work <folder> --recipe uvcharge --image my.s19
```

What the runner does:

1. Builds every image the tests need into `--work` with [`tools/build_recipe.py`](../tools/build_recipe.py)
   (each one must match its published SHA-256) and converts each to the global layout with
   [`tools/to_global.py`](../tools/to_global.py).
2. Imports the `rvc_status` image into a fresh Ghidra project (named emulator\_tests) in `--work`. That program is only a
   host: every test loads the image it is given into a read-only transaction, so a stale project can never
   test old bytes.
3. Runs each test listed for the recipe, one at a time, as a headless `-postScript` with `-readOnly -noanalysis`.
4. Prints `PASS` or `FAIL` per test and writes `<work>/<recipe>-<script>.log` (and `import.log`). A test
   passes when its output contains the `expect` line from `tests.json` and no "Abort due to" line. Exit
   status is 1 if any test fails.

Tests run one at a time because a Ghidra run locks its project. If Ghidra is open on the work project,
the runner stops and says so; close it and run again. The longest test, `UvChargeTest`, takes about
7 minutes.

`--image` runs one recipe's tests against your own image instead of the recipe build. The tests check exact
bytes, so an image that differs from the recipe fails at the first identity check, and the log says where.
That is the expected result for your own patch: it tells you the recipe's tests no longer describe your
image, and you need a test of your own (below).

Tokens in `tests.json` arguments: `{image}` is the image under test (global layout), `{stock}` the stock
global image, `{base:NAME}` another recipe's global image.

## What each test proves

| Recipe | Test | Arguments | What passing establishes |
|---|---|---|---|
| `rvc_status` | [`ReviewHeaterTest`](../ghidra/ReviewHeaterTest.java) | image | The manual heater command on CAN1: every DLC other than 8, wrong magic, RTR and a standard-ID alias are rejected without touching a running lease; acceptance only in system states 3 and 4, energising only in 3; the lease expires every PIT tick regardless of automatic heating, work mode 0, state 4 or DCDC not ready; the stock temperature debounce advances once per PIT tick and trips and releases at the stock thresholds; the full PIT divider calls the hook once per 100 interrupts; an OFF injected at every instruction boundary of the PIT hook, main heater output and PA3 writer is never overwritten. See [heater.md](heater.md). |
| `rvc_status` | [`Can1MaskTest`](../ghidra/Can1MaskTest.java) | image, stock, `3E`, `--copy-only` | The CAN1 alarm-mask copy path: the real register builder and MSCAN driver over full and partial reply windows, identical to stock when the mask is off, every enable-byte value and every bit of registers 1392–1394; retries, toggle/expiry interrupt interleavings across full and partial copies, and mask-byte mutations. `3E` is the 1392 low-byte mask the image must apply. See [alarms-and-contactors.md](alarms-and-contactors.md). |
| `rvc_status` | [`Can1BusyReleaseTest`](../ghidra/Can1BusyReleaseTest.java) | image, stock | The CAN1 busy-release fix: real receive/transmit sequences for 4, 5, 7, 8, 12 and 40-word requests release busy with the final frame; stock pacing, multi-frame holds and blocked-send retries are unchanged; a pacing interrupt or new request injected at every unmasked transmitter boundary loses nothing. Negative controls run the stock tail and show the drop. See [can-protocol.md](can-protocol.md). |
| `rvc_status` | [`HeaterStatusTxTest`](../ghidra/HeaterStatusTxTest.java) | image, stock | The 0x18FF7070 status broadcast: a 2 s cadence in every relay/state combination; exactly one acknowledgement per accepted command and none for dispatcher-rejected frames; the lease field is the live PIT countdown; a full transmit buffer drops a heartbeat, never an acknowledgement; register poll replies are byte-identical with the broadcast interleaved; both main-loop splices still execute the stock call they displaced; stack depth measured against the stock transmitter. |
| `cvl` | [`CvlSettingTest`](../ghidra/CvlSettingTest.java) | image, stock, `{base:rvc_status}`, `max=570` | The image is the `rvc_status` image plus three splices and `FE:BFE0–BFFF`; the charge voltage limit routine over all 65,536 IR3 values (52.0–57.0 V passes, anything else gives stock 54.7 V) writes nothing outside the stack; the "full" comparison equals the stock `CPD` against limit − 0.1 V for all 65,536 D values; registers 0x138F, 0x1391 and the `soc_calc` full rule follow IR3. `max=570` matches a `--cvl570` build. See [charge-limits.md](charge-limits.md). |
| `slew256` | [`ChargeSlewTest`](../ghidra/ChargeSlewTest.java) | image, stock, `{base:cvl}`, `256` | The image is the `cvl` image plus one splice at `FE:A96E` and `0x57E0–0x57FF`; every previous value × every taper output matches the model; through the real selection block, steady state equals stock, falls are limited to 1/256 per pass from the 90 A clamp, zero and rises pass at once. `256` selects the rate (`128` for a `--slew` build). |
| `uvcharge` | [`UvChargeTest`](../ghidra/UvChargeTest.java) | image, stock, `{base:slew256}` | The image is the `slew256` image plus splices at `FE:A96C`, `FE:AC0A`, `0x500C` and `FC:B780–B7FF`; the register copy hides the undervoltage bits until a PA1 cut is near, against the real builder for every word value; the 42 A / 21 A caps; real main-loop passes in scheduler order show the warning reaching the bus before PA1 opens, for cell and pack, including the charge-mode exit under load; the CAN4 0x100 work-mode byte is ignored while an L3 countdown runs. |
| `uvcharge` | [`Can1MaskTest`](../ghidra/Can1MaskTest.java) | image, stock, `16`, `--copy-only` | As above, with the `--diffmask` 1392 low-byte mask `16`, which also hides the difference L1 bits. |

### The `--copy-only` rule

Run `Can1MaskTest` with `--copy-only` on every image that contains the status broadcast, which is every
published recipe. The full mode also runs the command and producer fixtures, and those forbid two things
broadcast images do by design: the broadcast acknowledges each mask command with a CAN1 transmit, and the
`uvcharge` image hides cell UV L1 (1393 `0100`) at its source where the producer fixture expects stock. The
copy paths it still checks are unchanged by either. The command handling those fixtures skip is covered by
`HeaterStatusTxTest` (acknowledgements) and `UvChargeTest` (the hidden bits). The third argument is the
expected 1392 low-byte mask: `3E` by default, `16` for a `--diffmask` image.

### Tests not in `tests.json`

These are not part of any recipe's acceptance set.

| Script | Arguments | Use |
|---|---|---|
| [`EmulatorQuirkProbe`](../ghidra/EmulatorQuirkProbe.java) | any global S19 | Reports, without asserting, which emulator defects (below) the installed Ghidra still has. Run it under any Ghidra version other than 12.1.4. |
| [`DecoderHeaterTest`](../ghidra/DecoderHeaterTest.java) | image, stock | Differential test of the compact CAN4 ID decoder against the original 193 decoder bytes in a second emulator: every standard ID with and without RTR, every IDR1 value, single-bit extended IDs and 4,096 random extended patterns (8,820 cases), plus routing to all 22 command handlers in system states 0–5. |
| [`PreFlashExtraTests`](../ghidra/PreFlashExtraTests.java) | image, stock | Every CAN4 command handler on valid and invalid payloads with poisoned decoder scratch, per-write policies, every initial PORTA/relay-object combination, and OFF/renew/expiry at every instruction boundary of the PORTA writer. The base class of `Can1MaskTest`. |
| [`EmuHeaterTest`](../ghidra/EmuHeaterTest.java) | image | Basic heater activation, expiry and cancel; the base class of the older tests. |

Run one by hand against the runner's project:

```sh
<Ghidra folder>/support/analyzeHeadless <work> emulator_tests -process rvc_status_global.s19 -readOnly -noanalysis \
  -scriptPath <kit>/ghidra -postScript EmulatorQuirkProbe.java <work>/stock_global.s19
```

## Emulator quirks on this target

Ghidra's HCS12X emulator mis-executes a few things this firmware relies on. The firmware bytes are never
changed to work around them; the harness corrects the emulator.

| Quirk | Effect | Correction |
|---|---|---|
| BGE / LBGE (`2C`, `18 2C`) | Branch condition reversed. The correct rule (NXP HCS12 core guide) is branch iff N xor V = 0. | `VerifiedHeaterHarness.step()` computes the right next PC from the pre-instruction CCR and overrides PC. |
| BRSET (direct, extended, indexed forms) | Branch sense wrong. The decompiler shows the same inversion, so read `BRSET` tests in the listing, not the decompiler. | `VerifiedHeaterHarness` decodes each addressing form, reads the operand and sets PC; an addressing form it does not know fails the test instead of guessing. |
| Paging (`segment` p-code op) | Not implemented by the emulator. | `EmuHeaterTest.setup()` maps local addresses to global ones using PPAGE/RPAGE. |
| `LDS` (`LoadStack` p-code op) | Not implemented. | `VerifiedHeaterHarness.setup()` supplies it. |
| CPEX | Z flag not sticky, which breaks the stock 32-bit ID compare (`rt_lcmp`). | `EmuHeaterTest.step()` restores sticky Z after that instruction. |
| RTI | Modelled as CPU12, not CPU12X, so the stacked frame differs. | Interrupt fixtures stop before RTI and restore the interrupted registers explicitly. |
| 16-bit PC after a far jump | PC can hold the low half of a global address. | `step()` maps it back to the global address. |
| `writeMemory` of a buffer of 4 KB or more | The emulator adopts the caller's array as its memory instead of copying it, so later emulated stores change the caller's array. | Restore RAM baselines in chunks under 4 KB (the tests use 256 bytes) and read them back. |

The float runtime (`rt_fcmp`, `rt_fmul`, …) and the CodeWarrior switch helper (`rt_switch_table`) emulate
correctly. `VerifiedHeaterHarness.instructionModels()` checks the BRSET and BGE corrections against truth
tables (262,144 BRSET cases, 1,024 BGE/LBGE cases) and the `LDS` callback, so a test that calls it fails if a
correction is wrong. Only tests built on `VerifiedHeaterHarness` get the BGE and BRSET corrections;
`EmuHeaterTest` and `DecoderHeaterTest` do not.

## Writing a test for your own patch

Put a class in `ghidra/` that extends `VerifiedHeaterHarness` (the file name must match the class name).
The useful pieces:

| Member | Use |
|---|---|
| `loadImage()` | Loads script argument 0 (your global S19) into the read-only transaction and checks every byte. |
| `init()` / `setup(entry, page)` | Fresh emulator: I/O and RAM zeroed, pages set, a few stock RAM values the heater code needs. |
| `byteAt`, `word`, `reg`, `pc` | Read and write RAM (local addresses, e.g. `0x23BE`) and registers. |
| `step()`, `runTo(stop, bound)` | Execute with the corrections; `runTo` fails if `stop` is not reached within `bound` instructions. |
| `farEntry(entry)` | Enter a page-FE routine as if by `CALL`; it returns to `0x4010` with PPAGE restored. |
| `restoreStock(emu, start, end)` | Copy stock bytes (script argument 1) into an emulator, for a stock-versus-patched differential. |
| `Trace` | Records every non-stack write; with `allowed` and `enforce` set, fails on any write outside the allow-list. |
| `rejects(description, action)` | A negative control: the action must fail a check. |
| `instructionModels()` | Self-test of the BRSET/BGE/LDS corrections. |
| `check(condition, message)` | Assertion; counted in `assertions`. |

A test that earns trust does, in order:

1. **Identity.** Parse your image and its base image (another recipe, or stock) and check that they differ
   only at the addresses your patch declares, and by the expected count. The `image()` helper in
   `ChargeSlewTest` shows how.
2. **Model.** Write the intended behaviour as plain Java, and compare the real routine against it over every
   input you can enumerate (65,536 values of a 16-bit operand is cheap), then a random sample.
3. **Call site.** Run the routine through its real splice, with surrounding stock code, under a `Trace`
   allow-list, and check stack, PPAGE and CCR come back as they went in.
4. **Concurrency.** If an interrupt shares state with your code, inject it at every instruction boundary.
5. **Negative controls.** For each property, mutate one byte of the image (or restore the stock bytes) inside
   `rejects(...)` and show the test catches it. A check no mutation can fail proves nothing.
6. **Pass line.** Print a unique line, for example `MY PATCH PASSED`, only at the very end of `run()`.

Then add it to `tests.json` under a new key:

```json
"mypatch": [
  {"script": "MyPatchTest.java", "args": ["{image}", "{stock}", "{base:uvcharge}"], "expect": "MY PATCH PASSED"}
]
```

and run it with `--recipe mypatch --image my.s19`. A key that is not a published recipe needs `--image`;
`--all` tries to build every key as a recipe, so it fails on yours.

Addresses in tests are global ([firmware-map.md](firmware-map.md)): RAM local `0x23BE` is
`ram(0x23BE)`, page FE local `0xA96E` is `0x7FA96E`. [function-reference.md](function-reference.md) lists the
named routines a patch is likely to call or splice into, and [flashing-and-recovery.md](flashing-and-recovery.md)
covers what to have ready before you flash.
