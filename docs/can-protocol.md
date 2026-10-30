# CAN protocol

This document covers everything the V137.4.5 BMS does on CAN: the two configured controllers, every
frame the firmware accepts, its periodic broadcasts, the settings frames and how they persist, the
register polling the APS-500 uses on CAN1 and the stock bug that drops some of those polls, the RV-C
proprietary commands and status broadcast the patched images add, and what to expect from a
Raspberry Pi CAN interface when you listen in. Function and variable names are those in
`markup/functions.tsv` and `markup/labels.tsv`;
addresses are Ghidra global unless written `FE:xxxx` (page FE, local) or as a bare RAM address.

## The two buses

The S12XE has five MSCAN modules, but the firmware initializes exactly two. Both run at
250 kbit/s (BTR0 = 0x87, BTR1 = 0x3E, clock = 40 MHz bus clock) with acceptance filters fully open,
so the receive ISR sees every frame on the wire and filters in software.

| Controller | Registers | Pointer in RAM | Receive ISR | What it carries |
|---|---|---|---|---|
| CAN1 | `0x0180` (`CAN1_base`) | `0x2622` (`g_can_x_regs_ptr`) | `ISR_CAN1_rx` | The RV-C bus. The APS-500 polls registers here. The patched images take their commands here and send the status broadcast here. |
| CAN4 | `0x0280` (`CAN4_base`) | `0x261E` (`g_can4_regs_ptr`) | `ISR_CAN4_rx_commands` | The BMS's own command bus: settings, SoC set, bootloader entry, the 0x100 status message, and all the periodic 0x5xx broadcasts. Renogy's debug tool talks here. |

`ISR_CAN0_rx` matches `0x18FF50E5`, but it reads its controller base from RAM `0x2620`, which
nothing writes. No third controller is initialized, so that ISR never runs.

CAN1 has exactly one transmitter in the whole firmware: `can_settings_multiframe_tx`, called once
per main-loop pass. Despite its name, it sends the CAN1 register replies. Six of the seven `can_tx`
call sites use the CAN4 base; only that one uses CAN1's. Any patch that adds a CAN1 frame has to
send it from the main loop next to that call, never from an ISR, or the two senders claim the same
transmit buffer and corrupt a poll reply.

Host interface names such as `can0` and `can1` in examples are whatever your adapter is called; they
are not the BMS controller numbers.

## CAN4: frames the BMS accepts

`ISR_CAN4_rx_commands` processes these only while `g_sys_state` is 3 (running) or 4. `dN` is data
byte N.

| ID | Match | Effect |
|---|---|---|
| `0x011` (std) | d0..d1 = `55 41` ("UA") | Infinite loop, COP watchdog reset, bootloader entry. |
| `0x08F62DE2` (ext) | d0..d1 = "UA" | Bootloader entry, extended-ID form. |
| `0x100` (std) | any | Charger/vehicle status. d2 bit 7 = balance enable, d2 bits 6..0 = balance difference threshold (mV), **d3 = `g_work_mode`** (1 charge, 3 discharge), d4:d5 = a word the balance selector does not use, d6:d7 = balance reference (mV). See [cell-balancing.md](cell-balancing.md). |
| `0x20F` (std) | d0..d1 = `AA AA` | Writes SoC = d2 to `g_soc_float` only. `soc_calc2` recomputes SoC from remaining Ah on the next 100 ms pass, so this has no lasting effect. |
| `0x300` (std) | any | Copies 8 bytes to `g_msg300_data`, whatever the checksum. If d7 is the 8-bit sum of d0..d6 and d0 bit 2 is **set**, sets the reset bit (bit 3) on all 11 alarm objects. |
| `0x310`–`0x313` (std) | any | Copies 8 bytes to `g_msg310_data`..`g_msg313_data`. **0x311 is the working SoC set:** if the last 0x300 had d0 bit 0 set, SoC = d5 (integer %, no range check) and remaining Ah = SoC/100 × capacity. |
| `0x410`–`0x41A` (std) | any | Settings write; see [Settings frames](#settings-frames). |
| `0x500` (std) | d0..d1 = `AA AA` | d2 = 01 requests the settings dump (11 frames, 0x450–0x45A); d2 = 02 requests a second dump. |
| `0x00000999` (ext) | any | In stock, stores its bytes to `g_cmd999_param0`, `g_cmd999_param1`, `g_cmd999_byte6`, `g_cmd999_byte7` and RAM `0x240E`, which nothing reads. **In every published recipe this case is inert**; the commands moved to CAN1 (below). |

The 0x300 bit-2 test is a `BRSET` on `0x2680,#4`. Ghidra's decompiler shows it inverted, the same
way its emulator mis-handles `BRSET` on this target (see [emulator-testing.md](emulator-testing.md)).

## CAN4: periodic broadcasts

`can_periodic_tx_scheduler` runs in the 100 ms task and sends on CAN4 only.

| ID | Contents known |
|---|---|
| `0x501` | d0..d3 = pack current as a float, copied verbatim from RAM `0x22A6` (**negative while charging**); d4..d7 = SoC float. |
| `0x502` | Not decoded here. |
| `0x503` | Includes the maximum temperature. |
| `0x504` | d4 = maximum alarm level, d5 = alarm count, **d6 = relay byte** (below). |
| `0x505` | d0 = work mode; d3..d4 = the charge current request, 0.1 A (the same RAM value as register 0x1390). |
| `0x506`, `0x507` | Not decoded here. |
| `0x510`+ | Cell voltages, cells/4 + 1 frames. |
| `0x550`+ | Temperatures. |
| `0x401` | `AA AA …`, settings acknowledgement, sent on a flag (below). |
| `0x7FE` | Zeros, sent on a flag. |

The 0x504 relay byte (d6, `g_tx504_relay_byte`):

| Bit | Relay | Port A pin |
|---|---|---|
| 0 | discharge contactor, the pack's main path | PA1 |
| 1 | "DCDC" contactor, most likely the 12 V DC-DC supply | PA2 |
| 4 | precharge | PA0 |
| 5 | heater relay | PA3 |
| 2, 3, 6, 7 | always 0 | |

What each contactor does and when it opens is in [alarms-and-contactors.md](alarms-and-contactors.md).

## Settings frames

The 11 settings frames are one 88-byte block at RAM `0x2626` (`g_settings_frame410` onward), frame N
at `0x2626 + 8N`. Writes use ID `0x410 + N`; the dump answers with `0x450 + N`. Multi-byte fields
are **big-endian**. The full field list, with offsets, units and which frame byte each field sits in,
is the `bms_settings` struct in `markup/structs.tsv`. In summary:

| Frame | Fields |
|---|---|
| 0x410 / 0x450 | `AH`, `cvol_num`, `temp_num`, `module_num` |
| 0x411 / 0x451 | `ch_cur_h1`..`ch_cur_h3`, `dch_cur_h1` |
| 0x412 / 0x452 | `dch_cur_h2`, `dch_cur_h3`, `tvol_h1`, `tvol_h2` |
| 0x413 / 0x453 | `tvol_h3`, `tvol_l1`..`tvol_l3` |
| 0x414 / 0x454 | `cvol_h1`..`cvol_h3`, `ch_temp_h1`, `ch_temp_h2` |
| 0x415 / 0x455 | `ch_temp_h3`, `dch_temp_h1`..`dch_temp_h3`, `ch_temp_l1`..`ch_temp_l3`, `dch_temp_l1` |
| 0x416 / 0x456 | `dch_temp_l2`, `dch_temp_l3`, `cvol_l1`..`cvol_l3` |
| 0x417 / 0x457 | `diff_vol1`..`diff_vol3`, `diff_temp1`, `diff_temp2` |
| 0x418 / 0x458 | `diff_temp3`, `feed_cur1`..`feed_cur3`, `SoC1` |
| 0x419 / 0x459 | `SoC2`, `SoC3`, `IR1`..`IR3` |
| 0x41A / 0x45A | not decoded |

Temperatures are whole °C (low-temperature fields signed), cell voltages 1 mV, pack voltages 0.1 V,
currents 1 A. `IR1`–`IR3` are read by nothing in stock; the `--cvl` patch uses `IR3` as the charge
voltage limit ([charge-limits.md](charge-limits.md)).

### Persistence

- A received frame updates RAM at once, so the new value is live on the next 100 ms pass.
- Each frame sets one bit in `g_settings_dirty_bits`. **The 11 records are written to external
  memory only when all 11 frames have arrived** (the word reaches `0xFF07`). A partial set changes
  the running values and is lost at the next reboot.
- Frames 0x410–0x416 also restart a 3000-tick software timer (`settings_store` is a misnomer: it
  restarts that timer). When it expires, the pending bits are cleared and, if no record write is
  outstanding, the BMS sends the `0x401 AA AA` acknowledgement.
- Frames 0x417–0x41A restart a different counter whose only effect is a task-flag bit that nothing
  tests.
- Each frame is one record in external memory, loaded at boot. A record that fails to load after
  three tries is zeroed.

To change one setting and keep it, read the dump, change the field, and send all 11 frames back.
Renogy's debug tool does this: its Configuration button sends all 11. Verify by reading the dump
again.

## CAN1: register polling

The APS-500 (source address `0x80`) reads BMS registers with RV-C/J1939 proprietary-A frames.
`ISR_CAN1_rx` matches `{ID high, ID low & 0xFF00}` against `{0x18EF, 0x7000}`: DGN `EF00`,
destination `0x70`, priority 6, **any source address**. The requester's address is kept, and the
reply goes to `0x18EF{SA}70`. A priority-7 frame (`0x1CEF70xx`) does not match and is dropped.

| Request | Meaning |
|---|---|
| `18EF70xx#SSSSCCCC…` | d0:d1 = start register, d2:d3 = count, both big-endian. |
| `18EF{xx}70#…` | Reply: 4 registers per frame, big-endian words, one frame per 50 ms pacing tick. |

Two register windows are accepted, starting at 0x1388 and 0x13E3; start index + count must not
exceed 40, or the frame is dropped before any state changes. Replies come out of a RAM mirror at
`0x23AE + (reg − 0x1388) × 2`, built by `task_soc_or_balance` (a misnomer: it builds the CAN1
register values).

| Register | Contents |
|---|---|
| 0x1388 | First word of the APS-500's cycle; contents not decoded here. |
| 0x138A | Remaining and full capacity (scale not confirmed). |
| 0x138F | Charge voltage limit, 0.1 V (stock constant 54.7 V). |
| 0x1390 | Charge current limit, signed 0.01 A, negative = charge (`D6FC` = −105.00 A). |
| 0x1391 | Charge enable / "full" flag, set when the pack is at or above 54.6 V. |
| 0x1392–0x1394 | Alarm and status bits ([alarms-and-contactors.md](alarms-and-contactors.md)). |
| 0x1395 | Temperatures. |
| 0x13A5 | Read once at APS-500 startup. |
| 0x1402 | Model string, 8 registers (two reply frames), read at APS-500 startup. |

The charge voltage and current limits are covered in [charge-limits.md](charge-limits.md).

### Timing and the poll cycle

The reply waits for the pacing flag, set every fifth call of the 10 ms PIT, so replies go out on
50 ms ticks. Measured: reply-to-reply median 50.0 ms; request-to-reply median 48.5 ms, worst
109 ms. The APS-500's cycle and its off-by-one stray frame `18EF7080#0043432A007D7900` (which fails
the range check and must not be answered) are in [aps500-ws500.md](aps500-ws500.md).

Every accepted poll also sets a flag at RAM `0x26E0` that a timer clears after 6000 ticks without
polls. While it is set, `alarm_cellOV_eval` clears cell overvoltage L1 and L2 every pass; see
[alarms-and-contactors.md](alarms-and-contactors.md).

### The busy-release bug (stock)

Stock drops some polls. `ISR_CAN1_rx` accepts a request only while the busy byte `0x26DF` is zero.
`can_settings_multiframe_tx` sends one frame per pacing tick:

| Words remaining | Stock behavior |
|---|---|
| 1–3 | Sends, then clears the pacing flag and busy at once. |
| 4 or more | Sends 4 words, advances index and count, clears the pacing flag, returns. **Busy stays set.** |
| 0 | Clears pacing and busy without sending. |

So the final frame of any request whose length is a multiple of 4, which is every APS-500 poll,
leaves busy set until the main loop comes back to the transmitter. If the 100 ms task block runs
first, that is much longer than the APS-500's 1.3 ms turnaround, and its next request is silently
ignored. Measured on stock-equivalent code: 1392 answered 1 time in 4, 1395 22% of the time.

Every published recipe rewrites the 14-byte 4-word tail at `FE:AD5F` so that it tests the
decremented count itself: zero falls into stock's own count-zero completion, which clears busy with
the final frame as stock already does for 1–3 words; nonzero returns as before. It never reads the
PIT-owned pacing flag, so a pacing interrupt cannot route a remainder of more than 4 words into the
partial-copy path, which copies the whole remainder into an 8-byte stack buffer. With the fix,
every poll is answered (measured over 60 s: 1388 240/240, 138A 117/117, 138F 120/120, 1392 119/119,
1395 120/120).

One consequence: the APS-500 then sees 1392 every cycle, so an active L1 alarm reaches it reliably.

## CAN1: RV-C proprietary commands (patched images)

Stock accepts no commands on CAN1. Every published recipe adds two, as RV-C clause 6.7
proprietary DGNs on the same `0x18EF70xx` match the polls use. Stock already discards these frames
(their "start register" fails the range check), so a sender can be tested against stock first.

```
ID 0x18EF70xx (extended, priority 6, xx = sender's source address), DLC 8

MM MM 00 00 00 00 HH LL
│     │           └── lease, big-endian, 100 ms ticks; 0000 cancels
│     └── reserved, send zeros
└── AA 55 = manual heater,  4D 53 = CAN1 alarm mask
```

| Rule | Detail |
|---|---|
| Admission | Extended ID, not RTR, DLC exactly 8, a known magic, `g_sys_state` 3 or 4. Anything else is not a command and changes nothing. A command sent during boot is discarded, not queued. |
| Lease | `0001`–`FFFF` replaces the remaining lease (`FFFF` = 65,535 ticks ≈ 109 min 13.5 s). `0000` cancels. Expiry, reset or power cycle restores stock behavior. The two leases are independent. |
| Reserved bytes | The mask command requires d2..d5 = 0 and otherwise applies nothing. The heater command does not read them. Send zeros. |
| Byte order | **Big-endian**, not RV-C's usual LSB-first. A generic RV-C decoder misreads the lease (60 s shows as 2,253 s). |
| Source address | Any. `tools/rvc_command.py` uses `F9` (Service Tool, clause 7.2). The Firefly panel sends from `9F`; its "ignore alarms" button sends a 6000-tick mask lease and a zero lease to switch off. |
| Concurrency | The command test runs before the busy test, so a command is accepted even with a poll in flight, and leaves the poll state alone. |

What the heater lease does is in [heater.md](heater.md); what the mask hides is in
[alarms-and-contactors.md](alarms-and-contactors.md).

```sh
python tools/rvc_command.py --dry-run heater 300     # print the frame
python tools/rvc_command.py heater 300               # heat 5 minutes
python tools/rvc_command.py mask 600 --watch 3       # mask 10 minutes, show the replies
python tools/rvc_command.py heater 0                 # cancel manual heating
python tools/rvc_command.py --listen 10              # decode status broadcasts only
```

See [`tools/rvc_command.py`](../tools/rvc_command.py) for adapter options (`--interface`,
`--channel`). The raw frames, for `cansend`: `18EF70F9#AA55000000000BB8` (heat 5 min),
`18EF70F9#4D53000000001770` (mask 10 min), `18EF70F9#AA55000000000000` (cancel heater).

## CAN1: status broadcast `0x18FF7070` (patched images)

Every published recipe sends an RV-C proprietary-B frame, DGN `0FF70`, priority 6, source `0x70`:

- every 20 serviced 100 ms ticks (about 2 s), unconditionally, so a missing frame means the BMS
  is gone, not that the heater is off;
- once more within one main-loop pass of every accepted command. This is the application-level
  acknowledgement: the command is already applied when the frame is built, so it reports the
  result. Measured latency 0.6 ms to 25 ms.

| Byte | Field | Encoding |
|---|---|---|
| 0 | tag | always `0x70` |
| 1 | heater relay object, `g_relay_heater_PA3` | bit 0 stock request, bit 2 actual output, bit 3 ready |
| 2 | flags | bit 0 manual session armed, bit 1 alarm mask active, bit 2 over-temperature inhibit, bit 3 `g_sys_state` = 3 |
| 3 | charge low-temperature alarm object, `g_alarm_tempLow_heater` | bits 0/1/2 = L1/L2/L3 crossed, bits 6–7 = level |
| 4–5 | manual heater lease remaining | uint16 **little-endian**, 0.1 s |
| 6–7 | minimum cell temperature, `g_temp_min_0p1C` | int16 **little-endian**, 0.1 °C |

The two 16-bit fields are little-endian, the opposite of the command lease and the register
replies. Example: `18FF7070#70000A000000CC00` is heater off, mask active, running, 20.4 °C.

Reading it:

- A frame the dispatcher rejects gets no acknowledgement. A `4D53` frame with a nonzero reserved
  byte is acknowledged but applies nothing, so check byte 2 bit 1 rather than the frame's presence.
- The lease in bytes 4–5 is an upper bound on manual heating. The over-temperature inhibit and
  leaving state 3 open the relay while it keeps counting; byte 1 bit 2 is the truth about the relay.
  The recovery cutoff zeroes the lease and the armed bit in the same tick and is not acknowledged.
- The frame carries no mask time remaining.
- The BMS uses source address `0x70` without sending an address claim.

The frame costs about 0.02% of the bus. The 168 bytes that build and send it sit at `0x5738–0x57DF`
([firmware-map.md](firmware-map.md)). Its period is 20 serviced ticks, not exactly 2.000 s: measured
median 2.000 s with occasional +30 ms when the 100 ms task runs late.

## Everything else on CAN1

What the BMS ignores on the RV-C bus (it receives about 30 frames/s and discards all but
`0x18EF70xx`):

| ID | Period | What |
|---|---|---|
| `19FFFD80` | 500 ms | DC_SOURCE_STATUS_1 from the APS-500 |
| `19FEC880` | 500 ms | DC_SOURCE_STATUS_5 |
| `19FEA580` | 1 s | Non-standard DGN broadcast by the APS-500 (tracks its alternator output) |
| `18F00480` | 1 s | J1939 EEC1, engine speed |
| `19FECA80` | 5 s | DM_RV from the APS-500, where its faults appear |

RV-C has no battery-heater DGN, command or status, which is why both commands and the broadcast are
proprietary.

## Listening with a Raspberry Pi CAN interface

MCP2515-based CAN boards for the Raspberry Pi drop a small, steady fraction of received frames:
measured about 0.05% to 0.13% depending on bus load, all reported as receive overruns in
`ip -s -s -d link show`, with no CRC or bus errors. The chip holds only two received frames, so
bursts are where the loss lands. The 11-frame settings dump arrives about half a millisecond per
frame apart, and about 1.3% of dumps arrive incomplete. The BMS and the APS-500 never see this loss;
it happens inside the listener's controller, and every listener on that controller misses the same
frames. Raising interrupt-thread priority does not help.

- Retry an incomplete settings dump, and never act on a partial one.
- Verify every settings write against a full read-back of all 11 frames.
- Don't treat one missing acknowledgement as a rejected command; confirm with the next 2 s status
  frame.
- A controller with a real receive FIFO (for example an MCP2518FD board) avoids the problem.
