# Recipe `uvcharge`: the alternator can charge a low pack

| | |
|---|---|
| Build | `python tools/build_recipe.py <stock.s19> uvcharge` (flags `--cvl --cvl570 --slew256 --uvcharge --diffmask`) |
| SHA-256 | `a66ff1d50969024f68b40c0e0b074d9056280b65c36670ef66680d67cd1cb6e9` |
| Size | 37,626 programmed bytes |
| Status | emulated, not flashed. Every feature in it has run in service, in an image that differs only by the 57.0 V ceiling byte |
| Emulator tests | UvChargeTest (base `slew256`), Can1MaskTest `16 --copy-only` |

Everything in [`slew256`](slew256.md), plus two things.

## 1. Charging through undervoltage

Stock reports the undervoltage alarm bits that make the APS-500 stop charging (fault 57), so the alternator can't
recharge a low pack. This recipe hides them from the APS-500 and shows them again just before the BMS would open
PA1.

- **Hidden:** registers `1393` and `1394` are sent `AND FAFF`. That covers cell UV L1 (in 1393), cell UV L2 (in 1394)
  and pack UV L2 (in both). The BMS's own alarms and protection are unchanged.
- **Shown again before a PA1 cut.** The bits come back and the charge current limit (`0x1390`) goes to 0, so the
  APS-500 drops its field before the 48 V contactor opens:
  - while charging, 10 s before the end of the 300 s L3 hold;
  - while charging, as soon as the BMS starts counting net discharge (1 A or more, which ends charge mode after
    3 s) with the L3 countdown past 6 s: 3 s before the cut;
  - otherwise, 4 s before the 10 s L3 cut.

  The 3 s case is the one a load larger than the alternator's output produces, and it is the shortest lead the
  stock mode detection allows. The APS-500 polls the alarm registers about every 0.5 s and zeroes its field on the
  first read; measured, it drops its current within 0.38 s.
- **Current capped while the lowest cell is low:** 42 A at cell UV L2 (set below 3100 mV, cleared at 3250 mV) and
  21 A at L3 (below 3000 mV, cleared at 3150 mV while charging). Falls go through the slew; rises are immediate.
- **The CAN4 `0x100` message can't change the work mode while the pack is at UV L3** (either 10 s countdown
  running). Its balancing fields still apply, and outside L3 the work-mode byte is stored as in stock. Without this
  a `d3 = 3` frame would end charge mode and open PA1 on the next pass, ahead of any warning.

Patch sites:
- three splices: the current cap at `7FA96C`, the 1393/1394 copy at `7FAC0A`, and the CAN4 `0x100` work mode at
  `7F500C`;
- four 32-byte records at `FC:B780`, the free tail of page FC.

See [charge limits](../docs/charge-limits.md) and [alarms and contactors](../docs/alarms-and-contactors.md).

## 2. The alarm mask also hides the difference L1 alarms

While a `4D53` mask lease runs, the transmitted `1392` is `AND EB16` instead of `AND EB3E`, which also clears:

| Bit | Alarm | Stock threshold |
|---|---|---|
| `0020` | temperature-difference L1 | `diff_temp1` 15 °C |
| `0008` | cell-voltage-difference L1 | `diff_vol1` 500 mV |

The APS-500 faults on these (fault 51) and recovers on its own about 12 s after the alarm clears. In service the
likely trigger is the heater pad warming one probe while the mask is in use. The L2 difference bits (`0010`,
`0004`) stay visible. So do the BMS's own alarms, 0x504 and protection. Without a lease, reporting is unchanged.
It is one operand byte, `FE:ACFD` `3E` → `16`, in the shared mask copy helper.

## Before flashing

1. `python tools/audit_image.py <stock.s19> V137.4.5_uvcharge_patch.s19`
2. `python tools/run_emulator_tests.py --ghidra <Ghidra> --stock <stock.s19> --work work --recipe uvcharge`
   (about 7 minutes).
3. [Flashing and recovery](../docs/flashing-and-recovery.md). The APS-500 needs a GENERAL_RESET if PA1 has opened;
   see [the APS-500 guide](../docs/aps500-ws500.md).
