# Recipe `rvc_status`: heater, alarm mask, CAN1 fix, RV-C commands, status broadcast

| | |
|---|---|
| Build | `python tools/build_recipe.py <stock.s19> rvc_status` |
| SHA-256 | `bf02616f29837170cd52efb314d446631574f403670b8e7598ff4ac0064eac72` |
| Size | 37,434 programmed bytes (stock 37,266; cap 48,988) |
| Status | flashed and run on a battery in service |
| Emulator tests | ReviewHeaterTest, Can1MaskTest (`--copy-only`), Can1BusyReleaseTest, HeaterStatusTxTest |

This is the base every other recipe builds on. It changes nothing about protection: thresholds, alarm
evaluation, contactor logic and every setting are stock. It adds five things.

## 1. Manual heater override

Stock firmware has no command that turns the heater relay on. This adds a timed lease that can energize the heater
when stock logic would not. It is purely additive: cancelling it, or letting it expire, never turns off stock's own
automatic heating.

Two protections remain and cannot be overridden: the factory over-temperature inhibit (about 40.1 °C), and
self-cancellation once the coldest probe reaches the charge low-temperature L1 setting + 5 °C (10 °C with stock
settings). A command sent while the pack is already that warm is rejected. See [the heater guide](../docs/heater.md).

## 2. CAN1 alarm reporting mask

For the length of a lease, the alarm report sent to the charge controller hides these bits. Transmitted
`1392 &= EB3E`, `1393 &= FEFF`; `1394` is unchanged:

| Register | Bit | Meaning |
|---|---|---|
| `1392` | `1000` | Pack undervoltage L1 |
| `1392` | `0400` | Cell undervoltage L1 |
| `1392` | `0080` | Charge low-temperature L1 |
| `1392` | `0040` | Discharge low-temperature L1 |
| `1392` | `0001` | Heater logical output |
| `1393` | `0100` | Cell undervoltage L1 (duplicate) |

The BMS's own protection is untouched: alarm evaluation, relay-cut decisions, contactors, the telemetry table, the
CAN4 broadcasts and every L2/L3 alarm are unchanged. Only the outgoing CAN1 copy is masked. The Firefly panel's
"ignore alarms" button sends this command (source address 9F). Suppressing these bits lets the regulator miss
selected L1 conditions; see [alarms and contactors](../docs/alarms-and-contactors.md).

## 3. CAN1 poll fix (a stock bug)

After the last frame of any 4-register reply, stock keeps the request busy until the next main-loop pass and
silently drops a poll that arrives in that window, so the APS-500 misses many replies (1392 was answered about one
time in four). A rewritten 14-byte send tail at `FE:AD5F` releases busy with the final frame, as stock already does
for 1–3 register replies. Always on; there is no command. See [the CAN protocol guide](../docs/can-protocol.md).

## 4. RV-C commands

Both leases are commanded on CAN1, the RV-C bus, as an RV-C clause 6.7 proprietary DGN: ID `0x18EF70xx`, DLC 8,
where `xx` is the sender's source address. DGN `1EF00h` with DGN_Low `0x70` (the BMS), priority 6. Priority 7
(`0x1CEF70xx`) is rejected because the firmware compares the whole ID.

```
MM MM 00 00 00 00 HH LL
│     │           └── lease, big-endian, 100 ms units (seconds × 10); 0 cancels
│     └── send as zero (the mask command rejects nonzero; the heater ignores them)
└── AA 55 = heater,  4D 53 = alarm mask
```

| Action | `rvc_command.py` | Raw frame |
|---|---|---|
| Heat for 5 minutes | `rvc_command.py heater 300` | `18EF70F9#AA55000000000BB8` |
| Cancel manual heating | `rvc_command.py heater 0` | `18EF70F9#AA55000000000000` |
| Mask alarms for 10 minutes | `rvc_command.py mask 600` | `18EF70F9#4D53000000001770` |
| Restore normal reporting | `rvc_command.py mask 0` | `18EF70F9#4D53000000000000` |

- Every command is a lease, not a switch. The maximum is `FFFF`, about 109 minutes. Re-send to extend. Reset or power
  loss ends both leases. The two leases are independent.
- Commands are accepted only in system state 3 or 4 (running). A command sent during boot is discarded, not queued.
- `F9` is the RV-C Service Tool address, which needs no address claim. The BMS doesn't use the sender's address
  for acceptance.
- Stock's unused CAN4 debug command (extended ID `0x999`) does nothing in this recipe. Its bytes pay for the
  system-state gate.

## 5. Status broadcast

RV-C proprietary B `0x18FF7070` on CAN1, every 2 s and right after any accepted command. It reports the result
of the command, so it doubles as the acknowledgement. Decode it with `rvc_command.py --listen 10`.

```
70 RR FF AA LL LL TT TT
│  │  │  │  │     └── minimum cell temperature, little-endian, 0.1 °C signed
│  │  │  │  └── manual lease remaining, little-endian, 0.1 s
│  │  │  └── charge low-temperature alarm object (RAM 0x2416)
│  │  └── flags: b0 session armed, b1 alarm mask active, b2 over-temperature inhibit, b3 system state 3
│  └── heater relay object (RAM 0x2350): b0 request, b2 output, b3 ready
└── tag, always 0x70
```

- Byte 1 bit 2 tells you whether the relay is actually on. The lease is an upper bound: the over-temperature inhibit
  and leaving state 3 both open the relay while the lease keeps counting.
- A mask frame with a nonzero reserved byte is acknowledged but applies nothing, so byte 2 bit 1 shows the
  unchanged mask.
- The broadcast is unconditional, so a missing frame means the BMS is gone.
- It costs about 0.02 % of the bus.

This recipe adds 168 bytes at `0x5738`–`0x57DF`, in six records placed directly after the last stock record and
inside the erase sector it already occupies. It is the first image larger than stock, and the bootloader accepts
it. Nothing is transmitted from an interrupt: the command handler only arms a request bit, and the main loop
builds and sends the frame.

## Before flashing

1. `python tools/audit_image.py <stock.s19> V137.4.5_rvc_status_patch.s19`: no unreviewed bytes.
2. `python tools/run_emulator_tests.py --ghidra <Ghidra> --stock <stock.s19> --work work --recipe rvc_status`
3. [Flashing and recovery](../docs/flashing-and-recovery.md).
4. Afterwards, `rvc_command.py --listen 10` should show a status frame every 2 s.
