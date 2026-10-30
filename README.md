# Renogy RBT210LFP51S-12 BMS firmware kit

This kit is everything learned from reverse-engineering and patching the firmware of the Renogy RBT210LFP51S-12
48 V battery's BMS (version **V137.4.5**), and from how that BMS works with the Wakespeed WS500 / Renogy APS-500
alternator regulator on RV-C. These batteries are in the Jayco and Entegra coaches on Renogy's recall list.

With it you can:

- load **your own** stock firmware into Ghidra, fully annotated: every function the project identified is named
  and described, with the RAM variables, settings layout and alarm objects labeled;
- **rebuild the published patched images bit for bit**, and check each against its SHA-256;
- **audit and emulate** any image, published or your own, before you flash it;
- send the patched BMS's **RV-C commands** and read the APS-500's serial port;
- read what the BMS does, how it talks to the APS-500, and how to flash and recover.

**This repository contains no firmware.** You supply Renogy's stock `V137.4.5[20240719a].s19` from their update
package (SHA-256 `c589c76ae339e9a869f2e27b79ce2e0016b9eb38b469f8cf91ed74d838ee1932`).

> **Unofficial.** Not affiliated with or endorsed by Renogy or Wakespeed. Modifying and flashing battery firmware
> can damage equipment, disable protection, or leave the battery unable to start. You use this entirely at your
> own risk, and you decide which risks are acceptable.

## Quick start

You need Python 3.10+, and for the Ghidra parts, Ghidra 12.1.4 with JDK 21+. Then:

```
pip install -r requirements.txt
python tools/verify_stock.py "<path>/V137.4.5[20240719a].s19"
python tools/ghidra_setup.py --ghidra <Ghidra folder> --stock "<stock.s19>" --project <folder>
python tools/build_recipe.py --list
python tools/build_recipe.py "<stock.s19>" uvcharge
python tools/audit_image.py "<stock.s19>" V137.4.5_uvcharge_patch.s19
python tools/run_emulator_tests.py --ghidra <Ghidra folder> --stock "<stock.s19>" --work work --recipe uvcharge
```

Then flash with Renogy's tool: see [flashing and recovery](docs/flashing-and-recovery.md). New to Ghidra? Start
with the [Ghidra walkthrough](docs/ghidra-walkthrough.md).

## Recipes

Each recipe includes everything in the one above it. All four are built from the stock file by
`tools/build_recipe.py`, which fails unless the result matches the SHA-256 below exactly.

| Recipe | Adds | SHA-256 | Status |
|---|---|---|---|
| [`rvc_status`](recipes/rvc_status.md) | Manual heater and alarm-mask leases over RV-C, the CAN1 poll fix, a 2 s status broadcast | `bf02616f…` | flashed, run in service |
| [`cvl`](recipes/cvl.md) | The IR3 setting sets the charge voltage limit, 52.0–57.0 V (IR3 = 0 keeps stock 54.7 V) | `2a45aab9…` | emulated, not flashed |
| [`slew256`](recipes/slew256.md) | The charge current limit falls at most 1/256 per 100 ms instead of in one step | `102e2721…` | emulated, not flashed |
| [`uvcharge`](recipes/uvcharge.md) | The alternator can charge a low pack; the mask lease also hides the difference L1 alarms | `a66ff1d5…` | emulated, not flashed |

"Emulated, not flashed" means the exact image passes its emulator tests but hasn't run on a battery. For `slew256` and
`uvcharge`, every feature has run in service in an image that differs only by the one byte that raises the IR3
ceiling from 56.0 V to 57.0 V.

None of the recipes changes a protection threshold, the contactor logic or a stored setting. Build your own variant
with `tools/make_patch.py` and its flags, or by changing it.

## Documentation

| Guide | What it covers |
|---|---|
| [Ghidra walkthrough](docs/ghidra-walkthrough.md) | Install, import, annotate, find your way around, make a patch |
| [Function reference](docs/function-reference.md) | Every named function and variable, with what it does |
| [Firmware map](docs/firmware-map.md) | MCU, memory map and paging, address mapping, free flash, the size cap |
| [CAN protocol](docs/can-protocol.md) | Both CAN buses, commands, broadcasts, settings frames, RV-C commands, the status broadcast |
| [Alarms and contactors](docs/alarms-and-contactors.md) | Alarm objects and levels, what each level does, PA0–PA3, the alarm mask, what the APS-500 sees |
| [Heater](docs/heater.md) | Stock heater logic, the manual override and its protections |
| [Cell balancing](docs/cell-balancing.md) | How balancing works |
| [Charge limits](docs/charge-limits.md) | Charge voltage limit and IR3, the current taper, the slew, UV charging, building superseded variants |
| [APS-500 / WS500](docs/aps500-ws500.md) | Serial interface, polling, current limiter, voltage limit cycle, config strings, faults, GENERAL_RESET |
| [Emulator testing](docs/emulator-testing.md) | Running the tests, what they prove, writing one for your own patch, emulator quirks |
| [Flashing and recovery](docs/flashing-and-recovery.md) | Renogy's tools and settings, the two tools' differences, recovering a unit that won't start |

## Tools

| Tool | Does |
|---|---|
| `tools/verify_stock.py` | Checks that a file is the exact stock image (and explains a line-ending change) |
| `tools/to_global.py` | Converts a banked S19 to the global layout Ghidra uses |
| `tools/ghidra_setup.py` | Import, paging setup, analysis and markup in one command |
| `tools/build_recipe.py` | Rebuilds a published image and checks its SHA-256 |
| `tools/make_patch.py` | The patch builder behind the recipes, for your own variants |
| `tools/audit_image.py` | Lists every byte range an image changes, and flags anything a recipe doesn't produce |
| `tools/run_emulator_tests.py` | Runs the Ghidra emulator tests for a recipe or your own image |
| `tools/rvc_command.py` | Sends the heater and mask commands, decodes the status broadcast (python-can) |
| `tools/ws500_serial.py` | Reads and queries the APS-500 over USB serial; refuses config writes without `--allow-write` |
| `tools/gen_reference.py` | Regenerates the function reference from `markup/` |

Tests: `pytest`. Tests that need firmware or Ghidra skip unless `RENOGY_STOCK_S19` and `GHIDRA_HOME` are set.

## Contributing

Corrections and new findings are welcome as pull requests. Never commit firmware, vendor tools, Ghidra project
files or decompiler output. The pre-commit check (`git config core.hooksPath .githooks`, then
`python tools/scrub_check.py`) refuses them, along with home paths and email addresses.

## License

Code is MIT ([`LICENSE`](LICENSE)). Documentation, recipes and markup are CC BY 4.0 ([`LICENSE-docs`](LICENSE-docs)).
