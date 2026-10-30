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

`heater_control` runs every 100 ms. It sets the request bit only when **all** of these hold:

| # | Condition | Setting |
|---|---|---|
| 1 | `g_work_mode` = 1 (charging). In any other mode the request is cleared as soon as the output is on. | |
| 2 | Charge low-temperature alarm level ≥ 1, i.e. minimum temperature below charge low-temp L1. The alarm object is `g_alarm_tempLow_heater`. | `ch_temp_l1`, stock 5 °C |
| 3 | Minimum temperature (`g_temp_min_0p1C`) not below discharge low-temp L3, with +1 °C hysteresis. | `dch_temp_l3` |
| 4 | Not inside the cell-OV-L3 or below-L3 sub-state machines (`heater_ctrl_cellOV`, `heater_ctrl_belowL3`), whose retry counters (`g_heater_retry_cnt`) persist in external memory. | |

Work mode 1 comes from `mode_detect_chg_dch`: about 1 A of charge for 3 s. CAN4 `0x100` d3 can also
write the work mode, but mode detection re-derives it from measured current.

While stock heats, it sets bit 4 of the low-temperature alarm object (open the discharge contactor,
PA1) and clears bit 5 (keep PA2). Stock auto-heating therefore opens PA1 while charging; see
[alarms-and-contactors.md](alarms-and-contactors.md).

**Supervision** (`heater_supervise`, `heater_sup_current_when_on`, `heater_sup_when_off`): with the
heater on, charge current below 5.0 A for 15 s marks the heater failed and turns it off, retrying
after 10 s, then 10 min, then 30 min. Charge current above 18 A for 5 s clears the failure count.
Because the heater itself draws current, stock heating in practice needs a charge source that can
supply roughly 8 A or more.

**There is no stock CAN command that turns the heater on.** The only stock levers are forcing work
mode 1 with `0x100` (overridden by mode detection) and raising `ch_temp_l1` with settings frame
`0x415`, which still needs real charge current because of the supervision.

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
