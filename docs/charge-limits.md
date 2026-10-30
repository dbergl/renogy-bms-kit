# Charge limits

This guide covers what the BMS tells the charge controller about charging: the charge voltage limit
(register 0x138F) and how the patched images make it settable through the unused IR3 setting, the
"full" threshold that follows it, the stock charge current ladder (register 0x1390) and its gates, the
1/256 slew that keeps the ladder's steps from zeroing the APS-500's field, and charging a low pack
through undervoltage alarms. It ends with the `tools/make_patch.py` flag sets for every image in the
series, including the superseded ones. Addresses are Ghidra global ([firmware-map.md](firmware-map.md));
the regulator side is in [aps500-ws500.md](aps500-ws500.md).

## The registers

The APS-500 reads a 40-word register window over CAN1. `can_settings_multiframe_tx` answers from a RAM
mirror at 0x23AE, word `(register − 0x1388)`, all written by `task_soc_or_balance` every 100 ms.

| Register | RAM | Meaning |
|---|---|---|
| 0x138F | 0x23BC | charge voltage limit, 0.1 V |
| 0x1390 | 0x23BE | charge current limit, signed 0.01 A (negative = charge) |
| 0x1391 | 0x23C0 | charge enable flag, set while the pack is at or above the "full" threshold |
| 0x1392–0x1394 | 0x23C2– | alarm bits ([alarms-and-contactors.md](alarms-and-contactors.md#what-the-aps-500-sees-on-can1)) |

The APS-500 requests 0x138F with a count of 4, so one reply carries the voltage limit (word 0) and the
current limit (word 1). It uses only those two words. `can_periodic_tx_scheduler` also broadcasts the
negated 0x23BE, so the two views cannot disagree.

The regulator applies word 0 directly as its `TargetVolts`. Its only check rejects values outside
30.0–75.0 V, so any clamp has to live in the BMS.

## Charge voltage limit

Stock writes a constant 54.7 V (0x223) into 0x138F. It is not a setting, not temperature-compensated and
does not depend on SoC.

### The "full" threshold follows the limit

Two compares use 54.6 V = limit − 0.1 V: the 0x1391 flag, and `soc_calc`'s charger-full rule. `soc_calc`
sets SoC to 100 % and the SoC-full flag (bit 1 of RAM 0x2624) by three routes:

| Route | Condition |
|---|---|
| Charger full | charge mode, pack ≥ limit − 0.1 V, and less than 5.0 A of charge, for 3000 consecutive passes (5 min); any miss restarts the count |
| Cell rules | SoC > 80 % and less than 1.0 A of discharge, with the highest cell at 3.400 V (charge under 1.5 A, 10 min), 3.450 V (under 3 A, 5 min), 3.500 V (under 5 A, 2 min) or 3.530 V (under 5 A, 5 s) |
| Overvoltage | a cell or pack OV L3 alarm, at once |

While the SoC-full flag is set the published current limit is 0 A, which stops the charger. The flag
clears after 120 s of at least 1.5 A discharge.

If the limit moved below 54.6 V with the threshold left at stock, the charger-full rule could never fire
and SoC would drift charge after charge. That is why the patch moves both together.

The averaged current the BMS uses here (RAM 0x22A6) is negative while charging. `g_current_float`
(0x261A), used by heater supervision, has the opposite sign.

### IR3 sets the limit (recipes `cvl`, `slew256`, `uvcharge`)

The settings field **IR3** (settings frame 0x419 bytes 6–7, RAM 0x2674, 0.1 V) carries the limit. Stock
stores, saves and reports IR3 but nothing reads it, so it is free to reuse; its stock value is 0.

| IR3 | Charge voltage limit | "Full" threshold |
|---|---|---|
| 0 (stock), or outside 520–570 | 54.7 V, stock | 54.6 V, stock |
| 520–570 | IR3 × 0.1 V (52.0–57.0 V) | IR3 × 0.1 V − 0.1 V |

- A clamp routine in the last 32 bytes of page FE returns IR3 if it is in range, else 547. It replaces
  the stock `LDD #0x223`, and a second routine replaces the two `CPD #0x222` compares with "limit − 1".
- With IR3 = 0 the behaviour is byte-for-byte stock, including the first pass after boot. A record that
  fails to load from external memory is zeroed, which also gives stock.
- A new value takes effect on the next 100 ms pass. IR3 = 550 (55.0 V) is in service on a battery charged
  by an APS-500.
- Lowering the limit while charging can trip the regulator's `BatVolts ≥ TargetVolts + 0.40 V` field
  cut. That stops charging; it is not a fault.

### Setting IR3 with Renogy's debug tool

Renogy's KPT Debug Tool (VE3.3.0) sends settings from a JSON file. Its settings fields are read-only,
so IR3 can only come from the JSON import (or from reading the BMS back).

1. Read the BMS's current settings with the tool, or start from Renogy's parameter configuration JSON
   from the firmware update package, so every other field is what you want.
2. Set the key as a **JSON number**: `"IR3": 550` for 55.0 V. A string such as `"550"` imports as 0,
   which gives stock 54.7 V.
3. Import the file and press Configuration. The tool sends all eleven settings frames 0x410–0x41A, and
   the BMS saves them to external memory only when all eleven arrive ([can-protocol.md](can-protocol.md)).
   A partial set changes the running value and is lost at reboot.
4. Read back. The tool shows the stored value labelled "IR3" (from the 0x459 dump frame).

- To go back to stock, write `"IR3": 0`. Deleting the key changes nothing: the tool keeps the last value
  it read or imported.
- The tool's spin box accepts 0–10,000 and passes the value through unchanged, big-endian.
- No reflash is needed to turn the feature off: IR3 = 0 is stock.

### Above 56.0 V

The `cvl` recipe and its successors accept up to 57.0 V. Against the stock thresholds for 16 cells:

| Limit | Average cell | Stock threshold crossed |
|---|---|---|
| 56.0 V (IR3 560) | 3.500 V | none |
| 56.8 V (IR3 568) | 3.550 V | pack OV L1 (`tvol_h1` 56.8 V) and cell OV L1 (`cvol_h1` 3550 mV) |
| 57.0 V (IR3 570) | 3.5625 V | the same; still below pack OV L2 (`tvol_h2` 57.6 V) |

- **No warning reaches the APS-500 above 56.8 V.** Pack OV L1 sits in the 0x1392 high byte, which it
  ignores, and cell OV L1/L2 never trip while a charge controller polls
  ([alarms-and-contactors.md](alarms-and-contactors.md#cell-ov-l1l2-are-suppressed-while-a-charge-controller-polls)).
- **The next stop is cell OV L3** (`cvol_h3` 3650 mV), which opens PA1 after 3 s. At a 57.0 V target one
  cell 88 mV above the average reaches it; at 56.0 V the margin is 150 mV.
- `soc_calc`'s cell rules declare the pack full once the highest cell holds 3.530 V for 5 s, or 3.500 V
  for 2 min. At these limits that can happen before the pack reaches the 0x1391 threshold, and the full
  flag then sets the current limit to 0.

## Charge current limit

The current limit is not a setting. It is a staircase indexed by the highest cell voltage, after gates
for alarms and temperature.

### Gates

| Condition | Limit |
|---|---|
| pack OV, cell OV, high temperature or charge overcurrent at L2, or the SoC-full flag | 0 |
| minimum temperature ≤ 0.0 °C or ≥ 55.0 °C | 0 |
| minimum temperature below 5.0 °C | 20.00 A |
| otherwise | the ladder |

Undervoltage alarms do not gate the limit: at UV L1 and L2 stock asks for the full ladder value.

### The ladder

| Highest cell (peak-held) | Limit | Above 50 °C | C-rate (210 Ah) |
|---|---|---|---|
| < 3400 mV | 105.00 A | 63.00 A | 0.5 C (0.3 C hot) |
| 3400–3429 mV | 42.00 A | 42.00 A | 0.2 C |
| 3430–3459 mV | 21.00 A | 21.00 A | 0.1 C |
| 3460–3499 mV | 12.00 A | 12.00 A | 0.057 C |
| ≥ 3500 mV | 4.20 A | 4.20 A | 0.02 C |

The pack is 16s2p of 105 Ah cells (210 Ah). The 54.7 V limit, 105 A maximum, 4.2 A termination, the
0–55 °C window and the 20 A cold current match Renogy's published specification; the cell-voltage steps
are not published.

The ladder reads the highest cell through an asymmetric peak-hold: while charging the held value rises
at once and falls only when the cell drops 30 mV or more below it, or after 3000 passes (300 s).

| Constant | Address | Stock bytes |
|---|---|---|
| peak-hold release band, 30 mV | 7FA76D | `8C 00 1E` |
| peak-hold decay, 300 s | 7FA780 | `8C 0B B8` |
| charge voltage limit, 54.7 V | 7FA8A4 | `CC 02 23` |
| cold threshold 5.0 °C / current −20.00 A | 7FA8EB / 7FA8F0 | `8C 00 32` / `CC F8 30` |
| 3400 mV / −105.00 A | 7FA8FE / 7FA903 | `8C 0D 48` / `CC D6 FC` |
| 3430 mV / −42.00 A | 7FA90A / 7FA90F | `8C 0D 66` / `CC EF 98` |
| 3460 mV / −21.00 A | 7FA916 / 7FA91B | `8C 0D 84` / `CC F7 CC` |
| 3500 mV / −12.00 A | 7FA922 / 7FA927 | `8C 0D AC` / `CC FB 50` |
| −4.20 A | 7FA92E | `CC FE 5C` |
| the same ladder above 50 °C | 7FA937–7FA967 | |

The hot ladder is a byte-for-byte duplicate apart from its first tier and three branch offsets, so
merging the two frees 42 bytes of page FE. The published limit is stored at 7FA96E (`STD 0x23BE`), the
one writer of 0x23BE.

### Why stock's steps zero the regulator's field

The APS-500 zeroes its field when its measured current reaches about 1.18–1.20 × the target it was
given, and only scales the field between 1.0 × and that ([aps500-ws500.md](aps500-ws500.md)). At
equilibrium in a tier the current equals the tier's limit, so each step down arrives as a ratio of
`L(i) / L(i+1)`. Stock's first step is 90 A (the regulator's own clamp) to 42 A, a ratio of 2.14. Measured
on a running system with stock firmware: every such step drops the current to about 0 A, it stays there
about 2 s and takes about 6 s to settle at the new limit. The 30 mV peak-hold band is about the same as
the IR drop of that collapse, so the hold releases and the ladder jumps back to 105 A, which repeats as a
limit cycle until the pack has charged further.

The BMS also reports battery current (register 0x1388) in whole amps, though the unit is 0.01 A: stock
converts the float to an integer before multiplying by 100. At 21 A one count is 5 % of the target.

## The 1/256 slew (recipes `slew256`, `uvcharge`)

The patch limits how fast the published 0x1390 may fall. A 3-byte splice replaces the `STD 0x23BE`
at 7FA96E with a call to a routine in one 32-byte record at 0x57E0 (the last Tier 1 bytes of the unpaged window,
[firmware-map.md](firmware-map.md)). With T the ladder's value and O the previous published value:

| Case | Published |
|---|---|
| T = 0 (an L2 alarm, the 0 °C / 55 °C gate, SoC full) | 0, at once |
| T has more current than O (any rise) | T, at once |
| otherwise (a fall) | O reduced by 1/256 of itself (rounded up), capped at 90 A of current, and never less current than T |

No RAM is added (the previous value is 0x23BE itself) and only D and the condition codes change.
Steady state publishes exactly what stock does. The contactor and L3 paths do not read 0x23BE.

| Step | Stock | 1/256 | 1/128 (superseded) |
|---|---|---|---|
| 105 → 42 A | one pass | 19.2 s | 9.8 s |
| 42 → 21 A | one pass | 17.0 s | 8.7 s |
| 21 → 12 A | one pass | 13.3 s | 6.9 s |
| 12 → 4.2 A | one pass | 23.0 s | 12.3 s |
| 105 → 63 A (hot) | one pass | 9.1 s | 4.7 s |
| 105 → 20 A (cold) | one pass | 37.4 s | 19.0 s |
| worst fall in any 1 s | — | 1.059 × | 1.098 × |

A fall starts from 90 A, the APS-500's own clamp, rather than spending time on 105 → 90 A. The regulator
polls about every 0.5 s, so each reply is a 1–2 % fall.

- **Measured on a running system with the 1/256 image:** 90 → 42 A falls are clean, with no field
  collapse. At steady 42 A and 21 A limits the current holds at a median 0.98 × and 0.95 × of the limit.
  Most remaining dropouts follow engine RPM dips about every 255 s, which the APS-500 overshoots on
  recovery; they are a regulator/engine effect, not the limit.
- **With 1/128**, a fall that starts while the regulator is still ramping up can reach 1.14 × the falling
  limit and collapse the field. 1/256 removes that case.
- **Cost:** during a fall the pack takes more than the ladder value for up to about 19 s at the 3.400 V
  step, a bounded overshoot of a few millivolts.
- At low current the whole-amp current reporting makes one count a large share of the limit, so a
  42 → 21 A fall can still occasionally cut around 30 A.

## Charging a low pack (recipe `uvcharge`)

Stock does not stop the BMS asking for current at undervoltage, but the APS-500 faults (fault 57) on
cell UV L1 in 0x1393 and on the cell and pack UV L2 bits in 0x1393/0x1394. From a lowest cell of 3150 mV
it refuses to charge, and it recovers only about 15 s after the bit clears, which for cell L1 takes
3300 mV held 1 s. A resting low pack does not get there by itself.

The `uvcharge` recipe (`--uvcharge`, which needs `--slew` or `--slew256`):

1. **Hides the undervoltage bits** in 0x1393 and 0x1394 (`AND FAFF`): cell UV L1 in 0x1393, cell UV L2 in
   0x1394, pack UV L2 in both. 0x1392, every other bit, the BMS's own alarms, 0x504, the contactor
   logic and SoC are unchanged.
2. **Shows them again and sets 0x1390 to 0 before the BMS opens PA1 for undervoltage**, so the regulator
   drops its field before the 48 V contactor opens under load:

   | Path | Warning | PA1 opens | Lead |
   |---|---|---|---|
   | charge mode, the 300 s L3 hold runs out | hold reaches 2900 passes | after 3000 | 10.1 s |
   | charge mode at L3, a net discharge of 1 A or more starts (it ends charge mode after 3 s) with the L3 countdown at 6 s or more | first discharge pass | 30 passes later | 3.0 s |
   | not charging, at L3 | L3 countdown reaches 6 s | after 10 s | 4.1 s |

   The 3.0 s lead is the longest the stock 3 s mode debounce allows. Measured on a running system, the
   APS-500 drops from about 41 A to under 1 A within 0.18–0.38 s of the first reply carrying an alarm
   bit it acts on (charge low-temperature L1), and it reads the registers about every 0.5 s. A net
   discharge of 1 A for a single pass at L3 shows the bits and drops the request; when the discharge
   ends within 3 s the bits hide again and the request returns. The constant UV_DCH_WARN in
   `tools/make_patch.py` sets that trade-off.
3. **Caps the request by the cell undervoltage level:** at most 42 A at cell UV L2 (sets below 3100 mV,
   clears at 3250 mV) and 21 A at L3 (sets below 3000 mV, clears at 3150 mV while charging). Using the
   alarm level gives the BMS's own hysteresis. Falls go through the slew; rises are immediate. Pack UV
   hides and warns like the cell bits but does not cap.
4. **Ignores the work-mode byte (d3) of the CAN4 0x100 message while either L3 countdown runs**, so no
   command can end charge mode, or start the 300 s hold, at L3. Its balancing fields still apply, and
   below L3 the byte is stored as in stock ([cell-balancing.md](cell-balancing.md)).

The code is 126 bytes at FC:B780 (global 7FB780), the Tier 1 tail of page FC, plus three splices: the
ladder join in `task_soc_or_balance`, the 0x1393/0x1394 copy in the register build, and the 0x100
handler in `ISR_CAN4_rx_commands`.

Limits:

- It cannot revive a pack PA1 has already isolated: after 10 s at L3 without charging, PA1 is open and
  the APS-500 has no power.
- L3 sheds the 12 V DC-DC, resets SoC to 0 % and writes the lockout whatever the charge source
  ([alarms-and-contactors.md](alarms-and-contactors.md#undervoltage-l3-in-detail)). The L3 paths (hold,
  charge-mode exit, CAN mode gate, 21 A cap) are verified in emulation only.
- Measured on a running system: the APS-500 charges through cell UV L1 and L2 with no fault, and its
  `TargetAmps` follows the 42 A cap down and back with no fault. 21 A has not run on an APS-500.
- The lease mask still hides 0x1393 `0100` (cell UV L1); the warning relies on the L2 bits, so the mask
  does not weaken it.

## Building each image

Every image starts from stock V137.4.5 (`tools/verify_stock.py`). The published recipes rebuild with
`tools/build_recipe.py <stock.s19> <recipe>`; every other combination builds with
`tools/make_patch.py <stock.s19> <out.s19> [flags]`. All of these are deterministic, so the same flags
always give the same SHA-256.

| Flags | Image | SHA-256 | Programmed bytes |
|---|---|---|---|
| (none) | recipe `rvc_status` | `bf02616f29837170cd52efb314d446631574f403670b8e7598ff4ac0064eac72` | 37,434 |
| `--cvl --cvl570` | recipe `cvl`: IR3 to 57.0 V | `2a45aab9931e877cb701d111d515c9574c1ba1857152112de3efff61ea5865f6` | 37,466 |
| `--cvl --cvl570 --slew256` | recipe `slew256` | `102e272137500e96c41857899c6af5a1e093c147f24cd5a207a0fc7d6f4f5ed1` | 37,498 |
| `--cvl --cvl570 --slew256 --uvcharge --diffmask` | recipe `uvcharge` | `a66ff1d50969024f68b40c0e0b074d9056280b65c36670ef66680d67cd1cb6e9` | 37,626 |
| `--cvl` | superseded: IR3 to 56.0 V, no slew | `5c2e578ba2c499011c5655dfdfed2fb998a433e6956d5bcdb936f236cb11784d` | 37,466 |
| `--cvl --slew` | superseded: 56.0 V, 1/128 slew | `76232cb737bb8ca9cb592575a24bdb30cfd4887e4a14230a62cd389d469172aa` | 37,498 |
| `--cvl --slew256` | superseded: 56.0 V, 1/256 slew | `a5d0739fc48914d14d1dd6c9b8e2038b80455e7b4ab542d1fd00a3204213bc3a` | 37,498 |
| `--cvl --slew256 --uvcharge` | superseded: 56.0 V, UV charging, mask `EB3E` | `37c0867cdc4612f9a56230ec36bb245345376f789c8dccc584942372a157168e` | 37,626 |
| `--cvl --slew256 --uvcharge --diffmask` | superseded: 56.0 V, UV charging, mask `EB16` | `2c86504285f20997ff0c8902ceb39d01c585dcbe8ce63652d992684f8574f8b7` | 37,626 |

- **Without `--cvl570`** the clamp's upper bound is 560: IR3 passes 520–560 (52.0–56.0 V), and 561–570
  give stock 54.7 V. `--cvl570` changes one operand byte (7FBFEA, `30` → `3A`) and is refused without
  `--cvl`.
- **`--slew` instead of `--slew256`** gives the 1/128 rate.
- **Without `--diffmask`** the mask lease clears `1392 & EB3E`, which leaves the difference L1 bits
  (fault 51) visible. `--diffmask` changes one operand byte (7FACFD, `3E` → `16`).
- `--uvcharge` is refused without `--slew` or `--slew256`, because it publishes through the slew routine.
- Every image is under the 48,988-byte cap ([firmware-map.md](firmware-map.md)); `tools/audit_image.py`
  shows every byte an image changes before you flash it.

Hardware status of these images: the 56.0 V images built with `--slew`, `--slew256`, `--slew256
--uvcharge` and `--slew256 --uvcharge --diffmask` have run on a battery with an APS-500, and the last of
them runs in service. The `--cvl`-only image and the three 57.0 V recipes (`cvl`, `slew256`, `uvcharge`)
are verified in emulation and have not been flashed. `rvc_status` runs on hardware.

The emulator tests ([emulator-testing.md](emulator-testing.md)) check exact bytes against a recipe. For a
56.0 V image, the CVL test takes its default bound instead of `max=570`, and the slew test takes `128`
for a 1/128 image instead of `256`.
