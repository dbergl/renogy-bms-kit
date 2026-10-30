# Flashing and recovery

How to put an image on the battery with Renogy's own tools, what to check before you do, and how to get a unit
back to stock when a flash goes wrong. Everything here uses the files in Renogy's RBT210LFP51S-12 update package
(the "Update Instructions 137.4.5" folder). This kit doesn't redistribute them.

## What's in Renogy's package

| Folder | Contents |
|---|---|
| `1. Bootload/` | `KPT_BMU_Bootload_V1.01.exe` (the MFC tool), plus a copy of the Qt tool in `KPT_MC9S32_Bootload_upmachine(AP99P)/` |
| `1. Bootload NEW/` | `KPT_MC9S32_Bootload_upmachine.exe` (the Qt tool, byte-identical to the copy above, plus a CANalyst-II `ControlCAN.dll`) |
| `2. Firmware/` | `V137.4.5[20240719a].s19`, the stock image every recipe starts from |
| `3. KPT_Debug_ToolVE3.3.0/` | the debug tool used to read and write settings (including IR3, see [charge limits](charge-limits.md)) |
| `4. Configuration File/` | the stock settings JSON |
| `ITek Driver/` | the driver for the USB-CAN adapter Renogy ships |

The tools are Windows-only and talk through the ITek/ZLG USB-CAN adapter's `ControlCAN.dll`. On Windows 10/11 the
driver install needs driver-signature enforcement disabled once. Renogy's recall document walks through it.

## Before you flash

1. **Keep the stock file.** Copy `V137.4.5[20240719a].s19` somewhere safe and check it with
   `python tools/verify_stock.py`. Reflashing it is how you get back to stock.
2. **Audit the image:** `python tools/audit_image.py <stock.s19> <image.s19>`.
   - A published recipe shows `recipe <name>` and 0 unreviewed bytes.
   - Your own changes show up as `unreviewed` ranges, so you can see exactly what you are about to program.
3. **Check the size.** The audit fails an image over **48,988 programmed bytes**, the size of the largest
   unpatched image Renogy has released for this battery (137.4.1), which the bootloader accepts. Stock 137.4.5 is
   37,266. Every recipe is under 37,700.
4. **Emulate it:** `python tools/run_emulator_tests.py … --recipe <name>`. See [emulator testing](emulator-testing.md).
   Emulation proves the patched code paths. It doesn't model the bootloader, real interrupt timing or the battery's
   hardware.
5. **Stop anything charging or discharging hard.** The flash reboots the BMS, and the contactors open while it
   restarts.

## Flashing (Renogy's sanctioned procedure)

Renogy's recall document specifies the MFC tool, with these settings:

| Setting | Value |
|---|---|
| Device | USBCAN1 |
| Channel | 1 |
| Baud | 250K bit/s |
| CAN type | Standard |
| File type | S19 |

1. Open the image (`.s19`) in the tool.
2. Click **Download**, then **press and hold the battery's "Battery Switch Self Reset" button** and keep holding it
   while "Complete: %" climbs from 0 to 100 % (1–2 minutes). Release at 100 %.

Holding the button is what puts the MCU into its bootloader and keeps it there while the frames stream in.
Sending CAN alone doesn't start a download this way.

The Qt tool (`KPT_MC9S32_Bootload_upmachine.exe`) flashes the same files through the same bootloader. Leave
**"Not clear sector" unticked**.

## The two tools are not the same

| | MFC `KPT_BMU_Bootload_V1.01.exe` | Qt `KPT_MC9S32_Bootload_upmachine.exe` |
|---|---|---|
| Erase | none: it relies on whatever the bootloader erases itself | **range-erases each region first** (unless "Not clear sector" is ticked) |
| Record framing | up to 5 eight-byte frames (40 bytes) per S-record | one frame per ID in its list |
| Use in the recall | the one Renogy's document names | the newer, general variant |

The Qt tool sends one erase frame per region before programming: `[0, page, startHi, startLo, 0, page, endHi,
endLo]` on extended ID `0x08F633E2`, and waits for the BMS to echo each. The regions span from each region's first
record to its last: S1 below `0x8000`, the `0xC000` stub, and each S2 page. That erases whole spans, including
sectors no record touches.

Patched images give the MFC tool no format reason to fail. They match stock in line endings (CRLF), record types
and 32-byte records, need at most 37 bytes per record, and run in ascending order with no sector revisited.

## Recovery

The patches only rewrite application flash. The on-chip bootloader is separate, and nothing in a recipe touches
it. That is why reflashing has always recovered a bad application flash.

- **If a unit won't run after a flash** (no contactor cycle at startup, no CAN or serial data), flash again **with
  the other tool**. When one tool has failed, the other has recovered the unit.
- In particular, a unit that a successful-looking MFC flash left dead was recovered by the Qt tool's full-span erase
  and flash, after an MFC reflash of official firmware didn't help. What persisted is not established. Whatever it
  was, it survived the MFC tool's reflash but not the Qt tool's erase.
- **Recover to stock** by flashing `V137.4.5[20240719a].s19` the same way.
- **If the tool doesn't show "Download successful"**, flash the same image again. A missing completion message
  and a failed programming are different events. The completion message depends on the BMS's final reply to the
  tool, and a second attempt of the same image has completed normally.

## After flashing

- A published recipe broadcasts `0x18FF7070` on the RV-C bus every 2 s: `python tools/rvc_command.py --listen 10`.
- If PA1 opened during the flash or a test, the APS-500 may latch a fault and stop polling. It needs a
  GENERAL_RESET; see [the APS-500 guide](aps500-ws500.md).
- Test new behavior with short leases first: a 30 s heater lease, a 60 s mask lease.

## Flashing from Linux

There is no Linux port of either tool or of `ControlCAN.dll`, and the Windows tools don't work under Wine on
ARM. The bootloader protocol is plain CAN at 250 kbit/s, though, so any SocketCAN adapter
(`ip link set can0 up type can bitrate 250000`) can send identical frames.
- **Record and replay** is the lowest-risk path: capture one real flash from the Windows tool with `candump -L` on
  the same bus, including the button-timed entry, and replay its frames for your image.
- **Reimplementing the protocol** in python-can is possible. The MFC tool's strings lay it out:
  1. `Start Download`;
  2. the battery replies `Require data address`;
  3. 8-byte frames in 15-frame blocks;
  4. `Respond the end download`;
  5. `Download successful`.

  It is ACK-driven, with about 250 retries and per-phase timeouts. The address and data CAN IDs are operator
  settings in the tool's dialog, so take them from a capture.
