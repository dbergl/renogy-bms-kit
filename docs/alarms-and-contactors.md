# Alarms and contactors

This guide covers the BMS's eleven alarm objects and their three levels, what each level does to the
charge request and to the four Port A outputs (PA0–PA3), how alarms release, which alarm bits the
APS-500 / WS500 regulator reads on CAN1 and which of them it acts on, and the patched CAN1 alarm mask.
Addresses are Ghidra global addresses ([firmware-map.md](firmware-map.md)); thresholds are the stock
values from Renogy's V137.4.5 parameter configuration table, field names as in the settings frames
([can-protocol.md](can-protocol.md)).

## The rules that matter most

> **The main contactor (PA1) opens only at L3 for every voltage alarm.** Cell and pack over- and
> undervoltage L1 and L2 never open a contactor. L1 is a report; L2 is the last warning before the cut.
>
> **Undervoltage L3 also sheds PA2**, the output that most likely feeds the 48 V → 12 V DC-DC, and
> writes a lockout to external memory that survives reboots.
>
> **A raised UV L1/L2 threshold releases only at threshold + 150 mV (cell) or + 2.4 V (pack).** Moving
> `cvol_l1`/`cvol_l2`/`tvol_l1`/`tvol_l2` above the pack and back leaves the alarm latched until the pack
> charges past the stock threshold plus that margin.
>
> **Never raise `cvol_l3` or `tvol_l3` above the pack.** L3 undervoltage opens PA1, sheds the DC-DC,
> resets SoC to 0 % and writes the lockout.

The current and temperature alarms are the exception to "only at L3": their L2 opens PA1 after a
delay (see [What each alarm does](#what-each-alarm-does)).

## Alarm objects

Eleven one-byte objects at RAM 0x2410–0x241A (`g_alarm_obj0` … `g_alarm_socLow`), evaluated every
100 ms by `alarm_manager`. Every object has the same layout (`alarm_object` in `markup/structs.tsv`):

| Bit | Meaning |
|---|---|
| 0, 1, 2 | L1, L2, L3 threshold crossed |
| 3 | reset request: the evaluator clears the object and its counters |
| 4 | request: open the discharge contactor, PA1 |
| 5 | request: open the DC-DC contactor, PA2 |
| 6–7 | alarm level 0–3 (`alarm_level_from_bits`) |

`g_relay_cut_mask` (0x241B) is the OR of bits 4 and 5 over all eleven objects; `relay_output_update`
opens PA1 while its bit 4 is set and PA2 while its bit 5 is set.

| Object | Label | Alarm | Evaluator |
|---|---|---|---|
| 0x2410 | `g_alarm_obj0` | charge overcurrent (`ch_cur_h1`/`ch_cur_h2`) | `alarm_obj2410_eval` |
| 0x2411 | `g_alarm_obj1` | discharge overcurrent (`dch_cur_h1`/`dch_cur_h2`) | `alarm_obj2411_eval` |
| 0x2412 | `g_alarm_obj2` | cell voltage difference (`diff_vol1`/`diff_vol2`) | `alarm_obj2412_eval` |
| 0x2413 | `g_alarm_obj3` | temperature difference (`diff_temp1`/`diff_temp2`) | `alarm_obj2413_eval` |
| 0x2414 | `g_alarm_cellOV` | cell overvoltage | `alarm_cellOV_eval` |
| 0x2415 | `g_alarm_tempHigh` | high temperature: copy of the charge or discharge object | `alarm_hightemp_select` |
| 0x2416 | `g_alarm_tempLow_heater` | low temperature, plus the heater's contactor requests | `alarm_lowtemp_select` |
| 0x2417 | `g_alarm_cellUV` | cell undervoltage | `alarm_cellUV_eval` |
| 0x2418 | `g_alarm_totalOV` | pack overvoltage | `alarm_totalOV_eval` |
| 0x2419 | `g_alarm_totalUV` | pack undervoltage | `alarm_totalUV_eval` |
| 0x241A | `g_alarm_socLow` | low SoC (`SoC1`/`SoC2`) | `alarm_socLow_eval` |

The temperature objects come in pairs at 0x241C–0x241F (`g_alarm_chgTempHigh`, `g_alarm_dchTempHigh`,
`g_alarm_chgTempLow`, `g_alarm_dchTempLow`). The work mode picks one of each pair: in charge mode
(`g_work_mode` = 1) the charge thresholds apply, in discharge mode (3) the discharge ones. In charge mode
`alarm_lowtemp_select` copies only the level and threshold bits of the charge low-temperature object
into 0x2416 and keeps bits 4–5, which belong to the heater logic ([heater.md](heater.md)). The stock
overcurrent and difference L3 thresholds (`ch_cur_h3`, `dch_cur_h3`, `diff_vol3`, `diff_temp3`) are 0.

The 0x504 broadcast carries the highest level (d4) and the alarm count (d5).

## What each level does

| Level | Effect |
|---|---|
| L1 | Reported on CAN4 0x504 and in the CAN1 registers. No effect on the charge request or contactors. |
| L2 | Pack OV, cell OV, high temperature and charge overcurrent at L2 set the published charge current limit (register 0x1390) to 0 ([charge-limits.md](charge-limits.md)). Undervoltage L2 does not. Current and temperature L2 open PA1 after a delay. |
| L3 | Opens PA1 for every alarm that has an L3; temperature and undervoltage L3 also open PA2. |

## What each alarm does

Trip and release delays are in 100 ms main-task passes; "1 s" is 10 consecutive passes past the threshold.

| Alarm | Stock L1 / L2 / L3 | PA1 opens | PA2 opens |
|---|---|---|---|
| Cell OV | 3550 / 3600 / 3650 mV | L3 held 3 s | no |
| Pack OV | 56.8 / 57.6 / 58.4 V | L3 held 5 s | no |
| Cell UV | 3150 / 3100 / 3000 mV | L3 held 10 s (held off up to 300 s in charge mode) | L3 held 5 s |
| Pack UV | 50.4 / 49.6 / 48.0 V | L3 held 10 s (held off up to 300 s in charge mode) | L3 held 5 s |
| Charge overcurrent | 115 / 120 A / none | L2 held 5 s | no |
| Discharge overcurrent | 220 / 230 A / none | L2 held 15 s | no |
| High temperature, charging | 50 / 55 / 57 °C | L2 held 5 s | L3 held 1 s |
| High temperature, discharging | 55 / 60 / 62 °C | L2 held 5 s | L3 held 1 s |
| Low temperature, discharging | −10 / −20 / −30 °C | L2 held 5 s | L3 held 1 s |
| Low temperature, charging | 5 / 0 / −10 °C | driven by the heater logic | driven by the heater logic |
| Cell / temperature difference | 500 / 800 mV; 15 / 20 °C | no | no |
| SoC low | 20 / 10 % | no | no |

The overcurrent evaluators also clear an L2 that has been held 60 s, twice, and latch it on the third.

### Cell OV L1/L2 are suppressed while a charge controller polls

Each accepted CAN1 register request (`18EF70xx`, handled in `ISR_CAN1_rx`) sets a flag that a timer
clears after 6000 passes (10 min) without a poll. While it is set, `alarm_cellOV_eval` clears cell OV L1
and L2 and their counters every pass. With an APS-500 on the bus, only cell OV L3 (`cvol_h3`) ever
trips. This is stock behaviour.

## Undervoltage L3 in detail

| Step | Cell UV | Pack UV |
|---|---|---|
| L3 trips | below `cvol_l3` (3000 mV) for 1 s | below `tvol_l3` (48.0 V) for 1 s |
| PA2 (DC-DC) sheds, lockout written | 5 s at L3 | 5 s at L3 |
| PA1 cut request | 10 s at L3 | 10 s at L3 |
| L3 releases | `cvol_l3` + 150 mV for 1 s, charge mode only | `tvol_l3` + 2.4 V for 1 s, charge mode only |

- **Charge mode holds PA1 closed for up to 300 s.** In charge mode (at least 1 A of charge for 3 s,
  detected by `mode_detect_chg_dch`) `alarm_post_adjust` clears the PA1 cut bit every pass for up to
  3000 passes, so a charging pack can recover. The hold ends early as soon as the mode leaves 1, which
  happens after 3 s of at least 1 A net discharge, or when a CAN4 0x100 frame writes the work mode.
  PA1 then opens in the same pass.
- **At L3 SoC is reset to 0 %.**
- **The DC-DC lockout is persistent.** It is bit 0 of RAM 0x25C3, saved in the external-memory record
  at 0x25C0. PA2 stays off until SoC is at least 20 % and the lowest cell is at least 3.300 V, across
  reboots.

## Releasing an alarm

| Alarm | L1 releases at | L2 releases at | L3 releases at |
|---|---|---|---|
| Cell UV | `cvol_l1` + 150 mV, 1 s | `cvol_l2` + 150 mV, 1 s | `cvol_l3` + 150 mV, 1 s, charge mode only |
| Pack UV | `tvol_l1` + 2.4 V, 1 s | `tvol_l2` + 2.4 V, 1 s | `tvol_l3` + 2.4 V, 1 s, charge mode only |
| Cell OV | — | `cvol_h2` − 200 mV, 1 s | `cvol_h3` − 250 mV, 10 s, discharge mode only |
| Charge low temperature | `ch_temp_l1` + 5 °C, 1 s | `ch_temp_l2` + 3 °C, 1 s | `ch_temp_l3` + 5 °C, 1 s |

The release points are measured from the setting, not from the value the alarm tripped at. So when a
threshold is raised above the pack for a test and then restored, the alarm releases only once the pack
reaches the **restored** threshold plus the margin. For stock settings that means:

| Raised setting | Clears after the restore only when |
|---|---|
| `cvol_l1` | lowest cell ≥ 3300 mV |
| `cvol_l2` | lowest cell ≥ 3250 mV |
| `tvol_l1` | pack ≥ 52.8 V |
| `tvol_l2` | pack ≥ 52.0 V |

Below that, the alarm outlives the restore until the pack is charged. Settings changes are live at once
but are saved only when all eleven settings frames arrive ([can-protocol.md](can-protocol.md)).

## Contactors and outputs

`relay_output_update` drives Port A every 100 ms, **only while `g_sys_state` is 3** (running), through
`relay_write_portA`. The 0x504 broadcast reports the outputs in d6 (`g_tx504_relay_byte`).

| Output | RAM object | 0x504 d6 bit | Function | Driven by |
|---|---|---|---|---|
| PA0 | `g_relay_precharge_a_PA0`, `g_relay_precharge_b_PA0` | bit4 | precharge | the two contactor state machines |
| PA1 | `g_relay_discharge_PA1` | bit0 | main charge/discharge path; charging flows through it | `relay_discharge_sm`, closed unless `g_relay_cut_mask` bit 4 |
| PA2 | `g_relay_dcdc_PA2` | bit1 | most likely the supply to the 48 V → 12 V DC-DC | `relay_dcdc_sm`, closed unless `g_relay_cut_mask` bit 5 |
| PA3 | `g_relay_heater_PA3` | bit5 | heater | `heater_control`, gated by `heater_overtemp_inhibit` ([heater.md](heater.md)) |

Bits 2, 3, 6 and 7 of d6 are always 0. In each relay object, bit 0 is the request, bit 2 the actual
output and bit 3 a ready flag.

- **PA2 as the DC-DC supply is inferred from the wiring**, not from the code. Measured on a running
  system: with PA1 open and PA2 closed, about 1.3 A still flows from the pack.
- **The APS-500 is powered downstream of PA1.** When PA1 opens it loses its 48 V supply, and after PA1
  closes again it does not resume polling the BMS until it receives an RV-C GENERAL_RESET
  ([aps500-ws500.md](aps500-ws500.md)).
- While the stock heater runs, it sets bit 4 of the low-temperature object (open PA1) and clears bit 5
  (keep PA2).

## What the APS-500 sees on CAN1

The APS-500 polls the BMS's register window over CAN1 ([can-protocol.md](can-protocol.md)) and reads
the alarm bits in registers 0x1392–0x1394. The charge request (0x1390) and the alarm bits are separate
paths: an alarm that zeroes 0x1390 stops charging without a fault, while an alarm bit the regulator acts
on raises a fault and stops it polling.

### Register 0x1392

| Bit | Alarm | APS-500 reaction |
|---|---|---|
| `2000` | pack OV L1 | none: the regulator ignores the high byte |
| `1000` | pack UV L1 | none (measured: exposed for 1 s, no reaction) |
| `0400` | cell UV L1 | none |
| `0200`, `0100` | charge / discharge high-temperature L1 | none |
| `0080` | charge low-temperature L1, in charge mode | **fault 61**, field to 0, polling stops |
| `0040` | discharge low-temperature L1, in discharge mode | **fault 61** |
| `0020` | temperature-difference L1 | **fault 51** |
| `0010` | temperature-difference L2 | fault 51 |
| `0008` | cell-voltage-difference L1 | **fault 51** |
| `0004` | cell-voltage-difference L2 | fault 51 |
| `0001` | heater output status | stored, no reaction |

### Registers 0x1393 and 0x1394

They carry L2 bits, plus one L1: `1393` `0100` is cell UV L1 (the same source bit as `1392` `0400`).

| Bit | Alarm | APS-500 reaction |
|---|---|---|
| `1393` `0100` | cell UV L1 | **fault 57** |
| `1393` `0400`, `1394` `0400` | pack UV L2 | fault 57 |
| `1394` `0100` | cell UV L2 | fault 57 |
| other L2 bits | — | faults 57–59 |

There is no L3 bit: at L3 the L2 bits are set too.

### What this means in service

- **Low temperature, a low pack and difference alarms are the L1s that stop charging**, as faults 61,
  57 and 51. A heater pad warming one probe 15 °C above the others raises temperature-difference L1.
- **Overvoltage and high-temperature L1 never reach it**: pack OV L1 and high-temperature L1 sit in the
  ignored high byte, and cell OV L1/L2 are suppressed at source while it polls.
- **An L2 that zeroes the charge request stops charging with no fault.** A zero limit with no fault
  points to an L2.
- **Recovery is slow.** Measured on a running system: fault 61 asserts about 1 s after the bit appears
  and clears by itself about 15 s after the alarm releases; fault 51 cleared 11.8 s after its alarm
  released. Some faults latch until an RV-C GENERAL_RESET ([aps500-ws500.md](aps500-ws500.md)).
- **The regulator's fault log names the cause**: read it with `$RLF:` via `tools/ws500_serial.py`.

## The CAN1 alarm mask (patched images)

All published recipes add a leased mask. While a lease runs, the BMS clears selected bits in the copy of
0x1392/0x1393 it transmits on CAN1. The alarm objects, `g_relay_cut_mask`, the 0x504 broadcast and
every contactor decision are unchanged, so the BMS's own protection is untouched. The mask exists so the
regulator charges through early warnings; every L2 bit it would stop on stays visible.

| Image | 0x1392 sent as | 0x1393 sent as | Hidden |
|---|---|---|---|
| no lease (any image) | unchanged | unchanged | — |
| lease, recipes `rvc_status`, `cvl`, `slew256` | `AND EB3E` | `AND FEFF` | pack and cell UV L1, charge and discharge low-temperature L1, heater bit |
| lease, recipe `uvcharge` (built with `--diffmask`) | `AND EB16` | `AND FEFF` | the above, plus temperature- and cell-difference L1 (`0020`, `0008`) |

The difference L2 bits (`0010`, `0004`) stay visible in every image. `0x1394` is never masked by the
lease.

The `uvcharge` recipe also hides the undervoltage bits in 0x1393/0x1394 (`AND FAFF`) without any lease,
until a PA1 cut is near; it then shows them and requests 0 A so the regulator drops its field before the
contactor opens ([charge-limits.md](charge-limits.md#charging-a-low-pack-recipe-uvcharge)). This is the one place L2 is
hidden, because stopping charge is the wrong response to low voltage.

### Using the mask

The command is an RV-C proprietary frame on CAN1 ([can-protocol.md](can-protocol.md)):

| Action | Frame |
|---|---|
| Mask for 10 minutes | `18EF70xx#4D53000000001770` |
| Cancel | `18EF70xx#4D53000000000000` |

- `xx` is the sender's source address; the BMS accepts any. The priority must be 6.
- Bytes 2–5 must be zero. Bytes 6–7 are the lease in 100 ms ticks, big-endian (`FFFF` ≈ 109 min). A
  new command replaces the remaining lease. Zero, expiry or a reboot restores normal reporting.
- The BMS answers an accepted command with an extra `18FF7070` status frame. Byte 2 bit 1 (`0x02`) is
  **mask active**; the same frame repeats about every 2 s. A frame with a nonzero reserved byte still
  gets the reply but leaves the bit clear, so check the bit.
- The Firefly panel sends RV-C from source address 9F, and its "ignore alarms" button sends a
  6000-tick (10 min) mask lease and a zero lease to cancel.
- `tools/rvc_command.py mask 600 --watch 3` sends a 10-minute lease and decodes the replies.

The mask is sampled once per assembled CAN1 frame, so a toggle never produces a half-masked frame; a
frame already queued may still carry the previous mode.
