# Cell balancing

This page describes how stock V137.4.5 decides which cells to bleed, where that policy comes from
(a CAN4 message, not the BMS itself), what the hardware path looks like, and which other inputs
(serial/Modbus, CAN1, the CAN0 handler, XGATE) were checked and found not to touch balancing. It
also lists two stock defects in the Modbus parser. Everything here comes from the instruction
listing and targeted emulation of the stock image; none of it is an electrical measurement of
balancing current. No published recipe changes balancing.

## Summary

| Question | Answer |
|---|---|
| Who decides to balance? | Whoever sends CAN4 standard ID `0x100`. The BMS only applies the enable, reference and threshold it receives. |
| Is balancing on at power-up? | No. Startup zeroes the balance globals; balancing stays off until a `0x100` frame enables it. |
| Does the enable time out? | No. An accepted enable persists until overwritten or reboot. |
| Is balancing state visible on CAN? | No. The enable and the bleed mask are never copied into any CAN1 or CAN4 transmit buffer, and the selector sets no alarm bit. |
| Charge-mode, current, SoC or temperature gate? | None in the selector or its caller. |
| Kind of balancing | Passive: per-cell discharge bits written to the cell monitor over SPI. |

## The selector

The selector is an unnamed routine at `7F24F7`–`7F266B` (page FC `A4F7`–`A66B`). It runs every
100 ms from the acquisition task at `7F27CA`, after the cells are read and before temperatures,
in the main 100 ms slot ahead of the work-mode-zero skip. The acquisition-recovery path bypasses it.
`task_soc_or_balance` does not balance; despite its name it builds the CAN1 register data and the
charge current ladder ([charge-limits.md](charge-limits.md)).

Per monitor group (RAM stride `0x41`):

| RAM | Content |
|---|---|
| `251E + group×41` | cell count |
| `2520 + group×41 + cell×2` | cell voltage, mV |
| `253E + group×41` | bleed mask, one bit per cell (second group at `257F`) |

Global inputs, written only by the `0x100` decoder and startup:

| RAM | Content |
|---|---|
| `2518` | enable (1 = on) |
| `2519` | turn-on difference D, mV |
| `251C` | reference R, mV |

Rules per cell:

| Condition | Action |
|---|---|
| Vcell ≥ 2800 mV **and** (Vcell − R) > D **and** the qualifying-visit counter `25AE` has reached 10 | set the bit |
| (Vcell − R) < D − 10 | clear the bit |
| otherwise (including equality) | keep the bit |
| enable ≠ 1 | clear every group's mask |

- The counter `25AE` is shared by every cell and group. Each qualifying cell visit increments it;
  it is clamped to 10 when a bit is set. A non-qualifying cell does not reset it, and disabling the
  selector does not reset it. It is not a per-cell debounce or a one-second timer. Once primed,
  further qualifying cells switch on at once (emulated: with twelve qualifying cells and a zero
  counter, cells 10–12 turn on in the first pass).
- The 2800 mV floor gates only turning on.
- The hysteresis is 10 mV below the turn-on point. Example with R = 3300 mV, D = 20 mV, counter
  primed: 3321 mV turns on, 3320 mV does not newly turn on, 3310 mV keeps its state, 3309 mV turns
  off.
- The configuration is written to the monitor when a group's mask changes; the acquisition task
  also rewrites it separately.

## The policy source: CAN4 ID 0x100

The CAN4 handler (inside `ISR_CAN4_rx_commands`, `7F4FDB`–`7F5053`) decodes:

| Bytes | Meaning |
|---|---|
| d2 bit 7 | balance enable → `2518` |
| d2 bits 6–0 | difference D, 0–127 mV → `2519` |
| d3 | work mode → `g_work_mode` (1 = charge, 3 = discharge) |
| d4:d5, big-endian | word `251A`, clamped to 2000 if above 5000; not used by the selector |
| d6:d7, big-endian | reference R, mV → `251C`; a value below 2000 mV becomes 5000 mV |

The selector uses the **received** reference, not the BMS's own lowest cell. The same message sets
the work mode, so a frame sent to control balancing also sets charge or discharge mode. Commands are
accepted only in system states 3 and 4. The full CAN4 command set is in
[can-protocol.md](can-protocol.md).

## Hardware path

The configuration builder at FC `9DEE` packs each group's mask into configuration bytes `2539`/`253A`
(low 8 bits, next 4 bits) and writes the six configuration bytes to the cell monitor over SPI, reading
them back. FC `A032` starts a cell conversion. The layout matches the LTC6802 family (or a compatible
part): configuration byte 0 = `61`, discharge bits in byte 1 and the low nibble of byte 2, command
`01` write config, `02` read config, `10` start cell conversion. The monitor part on the board is not
identified, so the following is an inference from the LTC6802-2 datasheet:

- `61` selects CDC = 1, a 13 ms cell conversion; it is not a balancing duty cycle.
- During a conversion the monitor briefly interrupts the discharge switch of the cell being measured
  and its neighbours. A set mask bit therefore does not prove continuous bleed current.

Bleed current is a property of the board's resistors; the firmware only sets mask bits.

## Renogy's described behaviour against the firmware

| Described behaviour | In the V137.4.5 firmware |
|---|---|
| Only while charging | No gate. The selector's caller has no charge or work-mode test. |
| Starts when a cell reaches 3.4 V | Not in the selector; its floor is 2800 mV. The 3400/3430/3460/3500 mV ladder controls charge current, not balancing. |
| Cell spread above 60 mV | Mechanism only: turn-on is Vcell > R + D, which equals "60 mV above the lowest cell" only if the sender supplies R = lowest cell and D = 60. |
| 50 mA bleed | Hardware; not determinable from firmware. |
| 1 s on / 1 s off | Not found in any traced CPU path. |

If the described duty cycle happens on hardware, the candidates are an external controller sending
`0x100`, the monitor's measurement interruptions, or an untraced path.

## Other inputs checked

| Path | Finding |
|---|---|
| CAN1 (`ISR_CAN1_rx`) | Accepts only the `18EF70xx` register-read family; never decodes `0x100` or writes balance globals. |
| CAN0 handler (`ISR_CAN0_rx`, `7F55F6`) | Matches extended `0x18FF50E5` and copies 8 bytes to `2718`–`271F`. Nothing initializes its controller pointer `2620`, and its copy reads the CAN1 pointer; no balance decoder. |
| CAN2, CAN3 | Not initialized at startup. |
| XGATE | Two real handlers, both ADC servicing (read results, average three, store, select next channel). No CAN decode or balancing. |
| Serial / Modbus | Writes go to a separate telemetry table, not to balance globals or masks (below). |

Startup (`7F8A36`) sets the CAN4 pointer `g_can4_regs_ptr` to `0x0280` and `g_can_x_regs_ptr` to
`0x0180` (CAN1), then initializes each. A sender on the physical CAN4 network is the one established
way to enable balancing.

## Serial / Modbus

`ISR_SCI0_rx` reads the SCI0 data register and calls `sci_rx_handler`, which buffers bytes at `297C`
(count below 256); an inter-byte timer marks a frame ready for `task_pageFC_main`. The parser:

- answers slave address 1, functions `03`, `06` and `10`, and checks the Modbus CRC;
- maps registers 0–63 to RAM `28E8`–`2967` (`base + 2 × register`) for both reads and writes;
- calls a post-write callback through a pointer at `2B7E`, which startup sets to a single `RTC`
  (a no-op);
- has its table refreshed periodically (FE `BE7C`) with SoC, pack voltage and current, cell
  extremes, temperatures, alarms and identification, which overwrites most written words.

### Two stock parser defects

| Function | Defect | Effect |
|---|---|---|
| `06` (write single) | Rejects `register > start + count` instead of `>=` | Register 64 is accepted and overwrites RAM `2968`, the read-table base pointer, changing later read addressing. Register 65 is rejected. |
| `10` (write multiple) | Checks 16-bit `start + count` and a 16-bit doubled count, without first bounding count; the loop index is 8 bits | A malformed request with start = count = `0x8000` passes both wrapped checks, runs past the table and overwrites RAM `296A`, its own write-base pointer. Later writes in the request then land outside the table. |

Both are memory-corruption paths from malformed frames, not a balancing command. They were
reproduced by emulating the real parser on the stock image, stopping at the first corrupted pointer;
effects after that point are untested.

## Related

- [can-protocol.md](can-protocol.md): the `0x100` message and the other CAN4 commands.
- [charge-limits.md](charge-limits.md): the 3400 mV charge current ladder that is sometimes mistaken
  for balancing.
- [emulator-testing.md](emulator-testing.md): the corrected HCS12 harness used for these checks.
