# Firmware map

This page covers the hardware and memory layout of the Renogy RBT210LFP51S-12 BMS running stock firmware
V137.4.5: the microcontroller, the flash pages the image uses, how CPU addresses map to the global
addresses Ghidra shows (and that every address in this kit's markup uses), the vector table, the
main-loop timing, where free flash is and how much each region of it assumes, and the size cap for a
patched image. Function and variable names are the ones in `markup/functions.tsv`
and `markup/labels.tsv`; [function-reference.md](function-reference.md) describes them.

## Microcontroller

| Item | Value |
|---|---|
| Part | Freescale/NXP S12XE family (HCS12X CPU plus the XGATE coprocessor). Renogy's bootloader tools call it "MC9S32". |
| Clock | 4 MHz crystal, PLL to a 40 MHz bus clock (`clock_pll_init`) |
| CAN | 5 MSCAN modules on the part. Two are configured: CAN1 (`CAN1_base`, 0x0180) and CAN4 (`CAN4_base`, 0x0280), both 250 kbit/s, acceptance filters fully open. See [can-protocol.md](can-protocol.md). |
| Flash | P-flash, 1 KB erase sectors, 8-byte program phrases with ECC per phrase. A phrase can be programmed once per erase. |
| Toolchain | CodeWarrior, banked S-records (S1 for the unpaged window, S2 for paged pages), 32 data bytes per record, CRLF line endings, `S9030000FC` terminator |
| Ghidra language | `HCS-12X:BE:24:default`, loaded with the Motorola hex loader from a global-layout S19 ([`tools/to_global.py`](../tools/to_global.py)) |

## Flash pages the stock image uses

Programmed bytes in stock V137.4.5, 37,266 in all:

| Page | CPU window | Ghidra global | Bytes | Contents |
|---|---|---|---:|---|
| E0 | `E0:8800–8AD2` | `0x780800–0x780AD2` | 722 | XGATE vector table and XGATE code (ADC servicing only) |
| FC | `FC:8000–B77C` | `0x7F0000–0x7F377C` | 14,205 | Alarms, heater, settings storage, float runtime (`alarm_manager`, `heater_control`, `rt_fmul`, …) |
| FD (unpaged) | `0x4000–0x5736` | `0x7F4000–0x7F5736` | 5,983 | Startup, interrupt handlers, CAN receive and command dispatch (`ISR_CAN1_rx`, `ISR_CAN4_rx_commands`, …) |
| FE | `FE:8000–BFDF` | `0x7F8000–0x7FBFDF` | 16,352 | `main`, relays, SoC, CAN transmit (`can_periodic_tx_scheduler`, `task_soc_or_balance`, …) |
| FF | `0xC000` | `0x7FC000` | 4 | Entry stub: `JSR $4000` / `RTC` |

The vector table at `0x7F74–0x7FF9` is part of the FD figure. Page FE is full to 32 bytes short of its
end; it is the page in-place patches compete for.

## Paging

The CPU sees a 64 KB local map. Paged flash appears in the PPAGE window at `0x8000–0xBFFF`; the default
PPAGE is FE. Code in one page reaches another with `CALL addr,page` / `RTC`, which pushes and restores
PPAGE (3 bytes of stack per level). The unpaged window `0x4000–0x7FFF` (page FD) is visible from any
PPAGE and is reached with a plain `JSR`, which makes it the cheapest place for shared helpers. RAM is
local `0x2000–0x3FFF`; I/O registers are `0x0000–0x07FF`.

Patch documentation in this kit writes paged addresses as `page:local`, for example `FE:A96E`, and
unpaged ones as a bare local address, for example `0x5738`.

## Ghidra global addresses

Ghidra's HCS12X language uses one flat 24-bit address space. The kit converts the banked S19 with
[`tools/to_global.py`](../tools/to_global.py) and every address in `markup/`
is global.

| CPU address | Global address | Example |
|---|---|---|
| Page PP, local `0x8000–0xBFFF` | `0x400000 + PP × 0x4000 + (local − 0x8000)` | `FE:A96E` → `0x7FA96E`; `FC:B780` → `0x7F3780`; `E0:8800` → `0x780800` |
| Unpaged `0x4000–0x7FFF` (FD) | `0x7F0000 + local` | `0x5738` → `0x7F5738` |
| `0xC000–0xFFFF` (FF) | `0x7F0000 + local` | `0xC000` → `0x7FC000` |
| S1 records `0x8000–0xBFFF` | `0x7F0000 + local` (the default PPAGE FE window) | |
| RAM `0x2000–0x3FFF` | `0x0FE000 + (local − 0x2000)` | `g_relay_cut_mask` at `0x0FE41B` is RAM `0x241B` |
| I/O `0x0000–0x07FF` | unchanged | `PORTA` = `0x0000`, `CAN1_base` = `0x0180` |

Because pages FC, FD and FE are consecutive, the three code regions sit in one contiguous global range,
`0x7F0000–0x7FBFFF`. A disassembly at a global address still decodes paged operands as local
addresses; [ghidra-walkthrough.md](ghidra-walkthrough.md) shows how the setup scripts give Ghidra the
right PPAGE for each page so cross-page references resolve.

## Reset, startup and vectors

| Item | Detail |
|---|---|
| Bootloader | On-chip, separate from application flash, never touched by an application image. It enters the application through the word at `0x7FF8`, which holds `0xC000`. |
| `0xC000` stub | `JSR $4000` / `RTC`. Startup code at `0x4000` sets the stack pointer to `0x2250`, then calls `startup_copydown` (RAM initialisation) and `main` at `FE:8000`. |
| Vector base | `IVBR` = `0x7F`, so the table occupies `0x7F10–0x7FFF`; populated slots are `0x7F74–0x7FF9`. All stock and vendor releases (137.3.13, 137.4.1, 137.4.4, 137.4.5) share this stub, slot and table location. |
| Interrupt routing | `int_route_to_xgate_init` sends the ADC interrupts to XGATE; everything else runs on the CPU. |

Populated vectors (named by their standard S12XE slot, local address `0xFFxx` = table offset `0x7Fxx`):

| Slot | Handler | Purpose |
|---|---|---|
| FF7A | `0x4AAB` | PIT channel 0, 1 ms: task flags (below) |
| FF78, FF76, FF74 | `0x4B84`, `0x4B8E`, `0x4BBB` | PIT channels 1–3; channel 2 is the 10 ms measurement tick |
| FF92 | `ISR_CAN4_rx_commands` | CAN4 receive: the command bus |
| FFAA | `ISR_CAN1_rx` | CAN1 receive: the RV-C bus |
| FFB2 | `ISR_CAN0_rx` | CAN0 receive (handler present; CAN0 is not configured) |
| FFD6 | `ISR_SCI0_rx` | SCI0 serial receive |
| other populated slots | `0x55DB`, `0x55E4`, `0x55ED` | Shared default handlers |

The CAN and PIT handlers run with the CPU I bit set, so they never nest. Patches that share state between
a CAN handler and the PIT rely on that.

## Timing

`pit_init` sets up the PIT. Channel 0 interrupts every 1 ms and derives the main-loop task flags in
`g_main_task_flags`:

| Bit | Period | Work |
|---|---|---|
| 0 | 10 ms | COP watchdog kick (`ARMCOP`) |
| 1 | 50 ms | |
| 2 | 100 ms | The main task: alarms (`alarm_manager`), heater (`heater_control`), relay outputs (`relay_output_update`), SoC and charge limits (`task_soc_or_balance`), CAN transmit (`can_periodic_tx_scheduler`) |
| 3 | 1 s | LED |
| 7 | 500 ms | |

PIT channel 2 runs every 10 ms and does measurement and ADC work. `main` runs the CAN1 transmitter
(`can_settings_multiframe_tx`) on every loop pass and the 100 ms block when bit 2 is set. Counts in the
firmware ("3000 ticks", "30-tick debounce") are 100 ms passes unless stated otherwise. Flags can
coalesce if a main pass runs long, so a count of passes is not an exact wall-clock time.

`relay_output_update` acts only while `g_sys_state` is 3 (running). Most CAN commands are accepted in
states 3 and 4.

## Free flash and its risk tiers

New code must start on an 8-byte boundary past the last stock byte so no phrase is programmed twice,
use 32-byte records like stock, and be inserted in ascending address order directly after the record it
follows. Space is ranked by what it assumes about the bootloader.

### Tier 1: inside sectors stock already programs (360 bytes)

These bytes share a 1 KB sector with the last stock byte of their page, so they are erased whenever that
sector is, on every flash, whatever the erase policy. An image with added records here is accepted by the
bootloader and runs.

| Region | Last stock byte | Usable | Size | Used by the published recipes |
|---|---|---|---:|---|
| FD (unpaged) | `0x5736` | `0x5738–0x57FF` | 200 B | `0x5738–0x57DF` status broadcast (168 B, `rvc_status`); `0x57E0–0x57FF` charge-current slew (32 B, `slew256`) |
| FC | `FC:B77C` | `FC:B780–B7FF` | 128 B | undervoltage charging (128 B, `uvcharge`) |
| FE | `FE:BFDF` | `FE:BFE0–BFFF` | 32 B | charge voltage limit (32 B, `cvl`) |

The `uvcharge` recipe uses all 360 bytes. Space reclaimed inside existing stock code is separate: the
recipes replace the 193-byte CAN4 ID decoder at `0x4D78–0x4E38` with a 77-byte equivalent plus helpers,
and reuse the dead CAN4 0x999 debug handler at `0x5581–0x55CD`. [`tools/audit_image.py`](../tools/audit_image.py)
lists every region a recipe changes.

### Tier 2: the rest of pages the image already uses (about 12 KB)

| Region | Size | Note |
|---|---:|---|
| `0x5800–0x7BFF` | 9,216 B | Stop before `0x7C00`: that sector holds the vector table. The full page FD gap is `0x5738–0x7F0F`. |
| `FC:B800–BFFF` | 2,048 B | |

Vendor images 137.3.13 and 137.4.1 place ordinary code at `0x5737–0x5C4B` and `FC:B77D–BFBC`, so the
linker treats these as code space. What is unproven is the erase: a Tier 2 sector holds data the stock
image never writes, so it is blank at program time only if something erases it. Renogy's Qt bootloader
tool sends an explicit range erase for each region, from the file's first to last record, gap sectors
included; the MFC V1.01 tool sends no erase and relies on the on-chip bootloader. A sector that is not
blank (for example, holding bytes from an older release) fails to program or reads back wrong.
[flashing-and-recovery.md](flashing-and-recovery.md) covers the two tools.

A probe that cannot brick the BMS settles it: add one 32-byte record of a known constant at `0x5800`,
change no executable byte except a Tier 1 load-and-store that copies one byte of it into a spare byte of
a periodic CAN frame, flash, and read that byte off the bus. The expected constant means the sector
programmed; `FF` means it did not. Nothing branches into the probed sector, so failure is a wrong byte on
the wire, not execution of erased flash.

### Tier 3: a new page (16 KB)

Page FB, `FB:8000–BFFF`. Vendor 137.4.1 fills `FB:8000–A13B` with application code and calls into it
103 times, so the part and bootloader accept it. Code there needs `CALL`/`RTC` and a PPAGE-aware build.
Pages E1–FA are physically present (page E0 being populated implies at least 512 KB of P-flash) and
empty, but no release uses them.

## Size cap

A patched image may hold at most **48,988 programmed bytes**, the size of 137.4.1, the largest unpatched
image Renogy has released for this battery. The cap concerns size only; placement still follows the
tiers above.

| Image | S19 file bytes | Programmed bytes | Data records |
|---|---:|---:|---:|
| 137.3.13 | 117,852 | 48,430 | 1,523 |
| 137.4.1 | 119,202 | 48,988 | 1,540 |
| 137.4.4 | 92,026 | 37,815 | 1,191 |
| 137.4.5 (stock) | 90,688 | 37,266 | 1,172 |
| `uvcharge` recipe (all four recipes' additions) | | 37,626 | |

137.4.1 is larger mainly because of page FB, which 137.4.4 and 137.4.5 do not use; it comes from a
different source tree, not a different part. [`tools/make_patch.py`](../tools/make_patch.py) refuses to
write an image over the cap, and [`tools/audit_image.py`](../tools/audit_image.py) exits 1 on one.
