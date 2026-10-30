# Recipe `slew256`: the charge current limit falls gradually

| | |
|---|---|
| Build | `python tools/build_recipe.py <stock.s19> slew256` (flags `--cvl --cvl570 --slew256`) |
| SHA-256 | `102e272137500e96c41857899c6af5a1e093c147f24cd5a207a0fc7d6f4f5ed1` |
| Size | 37,498 programmed bytes |
| Status | emulated, not flashed. Its slew code ran in service in an image that differs only by the 57.0 V ceiling byte |
| Emulator tests | ChargeSlewTest at rate 256 |

Everything in [`cvl`](cvl.md), plus one thing: the reported charge current limit (register `0x1390`) can no
longer fall in one step.

## What it does

When the cell-voltage ladder lowers the limit, the reported value falls by at most 1/256 of itself per 100 ms
pass, about 3.8 % per second. For example, 90 → 42 A takes 19 s; stock does it in one step.

- A fall starts from 90 A, the APS-500's own cap, so a fall from a higher stock value doesn't stall above it.
- A cut to 0 A takes effect at once, whether from an alarm, the temperature gates or SoC full. So does any rise.
- This stops the large one-step cuts that made the APS-500 drop its field to zero.

Not changed: protection thresholds, contactors, the current ladder itself, and every setting other than IR3.

## Patch sites

One 3-byte splice at `7FA96E` replaces the `0x1390` store with a call to the slew routine, a 32-byte record at
`0x57E0`, in the unpaged window the status broadcast opened. `make_patch.py --slew` builds the faster 1/128 variant
of the same routine. See [charge limits](../docs/charge-limits.md).
