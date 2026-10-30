# Recipe `cvl`: the charge voltage limit follows IR3 (52.0–57.0 V)

| | |
|---|---|
| Build | `python tools/build_recipe.py <stock.s19> cvl` (flags `--cvl --cvl570`) |
| SHA-256 | `2a45aab9931e877cb701d111d515c9574c1ba1857152112de3efff61ea5865f6` |
| Size | 37,466 programmed bytes |
| Status | emulated, not flashed |
| Emulator tests | CvlSettingTest `max=570` |

Everything in [`rvc_status`](rvc_status.md), plus one thing: a settable charge voltage limit.

## What it does

Stock always reports a 54.7 V charge voltage limit to the charge controller (register `0x138F`). With this
recipe, the settings field **IR3** sets it. Stock stores, saves and reports IR3 but never reads it.

| IR3 | Charge voltage limit |
|---|---|
| 0 (stock default) | 54.7 V, exactly stock |
| 520–570 | 52.0–57.0 V, in 0.1 V steps |
| anything else | 54.7 V |

The SoC-full threshold and charge enable (register `0x1391`) move with the limit, to limit − 0.1 V. IR3 = 0
reproduces stock RAM effects byte for byte. Nothing else changes: protection thresholds, contactors, the current
ladder, and every other setting.

Set IR3 with Renogy's debug tool by importing a settings JSON with `"IR3": 550` (a number, not a string). The
tool's spin box can't be typed into. To go back to stock, write `"IR3": 0`. See
[charge limits](../docs/charge-limits.md).

## Above 56.0 V

A limit above 56.0 V sits against stock thresholds the APS-500 does not see:

| Limit | Average cell (16 cells) | Stock threshold crossed |
|---|---|---|
| 56.8 V (IR3 568) | 3.550 V | pack OV L1 (`tvol_h1` 56.8 V) and cell OV L1 (`cvol_h1` 3550 mV) |
| 57.0 V (IR3 570) | 3.5625 V | the same; below pack OV L2 (`tvol_h2` 57.6 V) |

- The APS-500 doesn't act on either L1. Pack OV L1 is in the 1392 high byte, which it ignores, and cell OV L1/L2
  never appear while a charge controller polls on CAN1.
- So no L1 warning reaches the regulator. The next stop is cell OV L3 (`cvol_h3` 3650 mV), which opens the
  contactor. At a 57.0 V target, one cell 88 mV above the average reaches it.
- The SoC-full rules set SoC to 100 % once the highest cell is at 3.530 V for 5 s (or 3.500 V for 2 min), which at
  these limits can happen before the pack reaches the 0x1391 threshold.

## Patch sites

- One 32-byte record at `FE:BFE0` holds `cvl_get` (IR3 clamped to 520–570, else 547) and `cvl_cmp`.
- Three 3-byte splices call them: the `0x138F` limit at `7FA8A4`, the full-threshold compare in the SoC
  calculation at `7F9537`, and register `0x1391` at `7FA974`.
- The 57.0 V ceiling is one operand byte, `FE:BFEA` `30` → `3A` (`CPD #560` → `CPD #570`). Building with `--cvl`
  alone gives the 56.0 V ceiling.
