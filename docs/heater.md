# Heater

This document covers the pack heater: how stock V137.4.5 decides to heat, why stock offers no way
to heat on demand, and the manual heater override every published recipe adds, including its
lease, its recovery cutoff and the over-temperature inhibit it keeps. The override is purely
additive: it can turn the heater on when stock would not, and it never turns off heating that stock
wants. The command frame and the status broadcast are defined in [can-protocol.md](can-protocol.md).

## Hardware path

| Item | Where |
|---|---|
| Relay | PA3, relay object `g_relay_heater_PA3` (bit 0 = request, bit 2 = actual output, bit 3 = ready) |
| Output stage | `heater_relay_output`: PA3 = request AND NOT `heater_overtemp_inhibit` |
| Reported | CAN4 `0x504` d6 bit 5; CAN1 status broadcast byte 1 (patched images) |
| Draw | Measured: pack current falls by about 7.5 A when the relay closes |

Relay outputs are written by `relay_output_update` every 100 ms, and only while `g_sys_state` is 3.

## Stock automatic heating

`heater_control` runs every 100 ms, after the alarm evaluators. It only heats in work mode 1
(charging); in any other mode the request is cleared as soon as the output is on.

**Work mode 1** comes from `mode_detect_chg_dch`. With PA1 closed: at least 1 A of charge for
3.1 s (31 passes). With PA1 open it compares voltages instead: `g_pa1_side_volt`, the charger side
of PA1, at least 2.0 V above the pack. Below 1 A either way the mode never changes on its own.

**The charge low-temperature alarm** (`g_alarm_chgTempLow`, copied into `g_alarm_tempLow_heater`)
is evaluated only in charge mode and restarts each time charge mode begins. L1 sets after the
coldest probe (`g_temp_min_0p1C`) is below `ch_temp_l1` (stock 5 °C) for about 1 s; it releases at
`ch_temp_l1` + 5 °C. L2 (`ch_temp_l2`, stock 0 °C) releases at + 3 °C.

In charge mode, in this order:

| # | Condition | Heater |
|---|---|---|
| 1 | Coldest probe below `dch_temp_l3` (`g_heater_below_floor`, released 1 °C above) | off |
| 2 | Charge low-temp L3 | `heater_ctrl_belowL3` if cell or pack UV L3 is also set; otherwise on under supervision |
| 3 | No low-temp level | off |
| 4 | L1 or L2 with cell or pack OV L3 | on, unsupervised |
| 5 | L1 or L2 otherwise | the state machine below |

The state machine is `heater_ctrl_cellOV` (a misnomer: it is the normal heating path):

| State | Heater | Leaves when |
|---|---|---|
| 0 | off | charge current above 10 A (RAM `0x22A6` below −10.0) for 31 consecutive passes, 3.1 s: to 1. Any pass at 10 A or less restarts the count. |
| 1 | on | more than 1 A of net discharge for about 1 s: back to 0. At L2, more than 10 A for 3.1 s: to 2. |
| 2 | on, PA1 opened (supervised, L2) | `heater_sup_current_when_on` reports a failure: to 4 |
| 3 | on, both cut bits set | the L2 bit changes |
| 4 | off, waiting | the retry delay ends and a charge flag is set: to 2 |

A change of the L2 bit returns any state to 0.

**What this means in practice:** at L1 (between 0 °C and `ch_temp_l1`) the heater starts only when
the charger pushes **more than 10 A** into the pack for 3 seconds without a break. Solar early in the
morning, a slow-ramping DC-DC charger, or a current that dips with engine speed may never start it.
The heater runs off the pack (it draws about 7.5 A), so once it is on the pack sees the charger's
current minus the heater's. If that goes net negative for a second the heater stops, and it needs
more than 10 A again to restart. Below 5 °C stock also asks the charger for at most 20 A
([charge-limits.md](charge-limits.md)).

**Supervision** (`heater_supervise`, `heater_sup_current_when_on`, `heater_sup_when_off`) applies
only in state 2 and at L3. Despite the name it watches a **voltage**: `g_pa1_side_volt` below 5.0 V
for 15 s (the charger has gone away while PA1 is open) marks the heater failed and turns it off,
retrying after 10 s, then 10 min, then 30 min. Above 18.0 V for 5 s clears the failure count.

**The retry counter** `g_heater_retry_cnt` (kept in external memory) locks heating out at 3 or more.
It only increments when the heater runs during cell or pack UV L3, and `heater_control` clears it on
every pass while SoC is 20 % or more.

While stock heats in state 2 or at L3, it sets bit 4 of the low-temperature alarm object (open the
discharge contactor, PA1) and clears bit 5 (keep PA2); see
[alarms-and-contactors.md](alarms-and-contactors.md).

**There is no stock CAN command that turns the heater on.** The only stock levers are forcing work
mode 1 with `0x100` (mode detection overrides it after 3.1 s of 1 A or more either way) and raising
`ch_temp_l1` with settings frame `0x415`, which still needs more than 10 A of real charge current
to start the heater.

## Over-temperature inhibit (stock, kept)

`heater_overtemp_inhibit` compares the heater-check temperature `g_temp_heaterchk_0p1C` with fixed
limits and sets `g_heater_overtemp_flag`:

| Condition | Result |
|---|---|
| Above 40.0 °C, held for a 30-tick debounce | Inhibit set, PA3 forced off |
| 39.1 °C to 40.0 °C | Inhibit keeps its state |
| 39.0 °C or below | Inhibit released |

No command overrides it. A manual lease keeps counting while the inhibit holds the relay open.

## Manual override (every published recipe)

The heater command is `AA 55` on CAN1 as an RV-C proprietary DGN, `0x18EF70xx`, with a big-endian
lease in 100 ms ticks ([can-protocol.md](can-protocol.md#can1-rv-c-proprietary-commands-patched-images)).

```sh
python tools/rvc_command.py heater 300     # 5 minutes
python tools/rvc_command.py heater 0       # cancel manual heating
```

### Semantics

| Property | Behavior |
|---|---|
| Additive | Effective request = stock request OR manual lease. The patch writes only the relay object's output bit and PA3, preserving every other Port A bit, and **never modifies the stock request bit** (byte 1 of the status frame reads `04`, not `05`, with only manual heating on). |
| Cancel | A zero lease cancels only the manual lease. If stock wants heat, the relay stays on. |
| Lease | A positive lease replaces the remaining one. It expires at the Nth following PIT tick, so its length is quantized by up to 100 ms. Maximum `FFFF` ≈ 109 min 13.5 s. No startup default arms a session: the startup zero-fill covers RAM `0x2250–0x2C10`. |
| States | Accepted in system states 3 and 4; the relay is energized only in state 3. Leaving state 3 drops the output while the lease keeps counting, and it re-energizes on return if time is left. |
| Current | The manual output bypasses the stock work-mode and charge-current conditions. Stock supervision still runs and can interact with automatic retry state. |
| Malformed frames | Wrong ID, RTR, DLC other than 8, wrong magic: no effect on the current lease. |

### Recovery cutoff

A manual lease cancels itself once the **coldest** probe reaches charge low-temp L1 + 5 °C:

```
min temperature (0.1 °C)  >=  10 × signed(ch_temp_l1) + 50
```

With stock `ch_temp_l1` = 5 °C the cutoff is 10 °C, the same temperature at which stock heating
releases its alarm. The comparison is immediate (no debounce), so it works outside charge mode.

- A command sent while already at or above the cutoff is accepted and cancelled in the same
  interrupt; the relay never closes. Its acknowledgement shows the session not armed.
- Cooling again does not revive a cancelled lease. Send a fresh command.
- The cutoff applies in state 4, alongside a stock request, and while the over-temperature inhibit
  is active.
- `ch_temp_l1` is read live from RAM, so changing it with settings frame `0x415` moves the cutoff
  at once. Raising it raises the cutoff.
- It uses the minimum probe, not the maximum: one cold probe keeps manual heating eligible while
  another is warm. The over-temperature inhibit is the upper bound.
- The cutoff clears only the manual lease. An independent stock request can still hold the relay
  on.

### Timing and concurrency

- The PIT interrupt owns expiry: each real 100 ms divider event decrements the lease, whatever the
  stock request, work mode, DC-DC readiness or coalesced main-loop task flags. It then runs the
  stock inhibit routine once and reconciles the output. During a session, the main loop's output
  calls reuse that result, so the debounce advances once per tick, not per call.
- An accepted command takes effect in the CAN interrupt itself, using the current inhibit result.
- CAN publication and PIT expiry both run with interrupts masked, and the main-loop decision and
  PA3 write use short critical sections that restore the previous CCR. A cancel can't be
  overwritten by a stale "on" write.
- After cancel or expiry the session is cleared and ordinary stock temperature evaluation resumes.

### Watching it

The status broadcast `0x18FF7070` reports the relay object, the armed flag, the inhibit flag, the
lease remaining and the minimum cell temperature, and is sent again right after every accepted
command ([can-protocol.md](can-protocol.md#can1-status-broadcast-0x18ff7070-patched-images)).
`python tools/rvc_command.py --listen 30` decodes it. Treat the lease field as a ceiling: byte 1
bit 2 is the actual relay state.

Measured with a 30 s lease and the probe below the cutoff: the relay closed, pack current fell by
7.5 A, the lease fell exactly 20 counts per frame, and the session expired on its own at 29.9 s.

## Testing heating in warm weather

Manual heating is eligible while the coldest probe is below `ch_temp_l1` + 5 °C. Setting
`ch_temp_l1` less than 5 °C below the pack temperature, but not above it, makes manual heating
eligible without raising any alarm. Setting it above the pack temperature raises charge low-temperature L1, which starts
stock heating in charge mode and puts bit `0080` (or `0040` in discharge mode) into register 1392.
The APS-500 faults on that bit, so mask it or expect a regulator fault
([alarms-and-contactors.md](alarms-and-contactors.md), [aps500-ws500.md](aps500-ws500.md)). Keep
`ch_temp_l3` above `dch_temp_l3`. Restore the stock value afterwards by sending all 11 settings
frames and reading them back ([can-protocol.md](can-protocol.md#persistence)).
