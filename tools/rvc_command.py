#!/usr/bin/env python3
"""Send the patched BMS's heater and alarm-mask commands on the RV-C bus (the BMS's CAN1).

The commands are RV-C clause 6.7 proprietary DGNs: DGN 1EF00h with DGN_Low set
to the BMS's source address (0x70), priority 6, so the 29-bit ID is
0x18EF70xx, where xx is the sender's address. Data: magic (AA55 heater, 4D53
mask), four zero bytes, then the lease in 100 ms ticks, big-endian. A zero
lease cancels. The BMS accepts commands only while it is running (system state
3 or 4), and answers each accepted one with its status broadcast, 0x18FF7070.
Every published recipe accepts these; stock firmware ignores them.

    python tools/rvc_command.py --dry-run heater 300      # print the frame only
    python tools/rvc_command.py heater 300                # heat for 5 minutes (Linux SocketCAN can1)
    python tools/rvc_command.py mask 600 --watch 3        # mask for 10 min, then show the status replies
    python tools/rvc_command.py --interface canalystii --channel 0 heater 0   # cancel, via a USB-CAN adapter
    python tools/rvc_command.py --listen 10               # just decode status broadcasts

Sending needs python-can (pip install python-can) and a CAN adapter python-can
supports, e.g. socketcan (Linux), canalystii (ITek/ControlCAN USB-CAN
adapters), pcan or slcan. The bus is 250 kbit/s.
"""
import argparse
import struct
import sys
import time

MAGIC = {'heater': 0xAA55, 'mask': 0x4D53}
BMS_ADDRESS = 0x70
SERVICE_TOOL = 0xF9          # RV-C clause 7.2 default source address for a service tool
STATUS_ID = 0x18FF7070       # proprietary B, DGN 0FF70, source 0x70


def frame(command: str, ticks: int, source: int = SERVICE_TOOL, dest: int = BMS_ADDRESS) -> tuple[int, bytes]:
    if not 0 <= ticks <= 0xFFFF:
        raise ValueError(f'lease out of range: {ticks} ticks (0-65535, 100 ms each)')
    return 0x18EF0000 | dest << 8 | source, struct.pack('>HIH', MAGIC[command], 0, ticks)


def parse_status(data: bytes) -> dict:
    """Decode the 0x18FF7070 status broadcast. Its 16-bit fields are little-endian."""
    if len(data) != 8 or data[0] != 0x70:
        raise ValueError(f'not a BMS status frame: {data.hex()}')
    relay, flags, lowtemp = data[1], data[2], data[3]
    lease, temp = struct.unpack('<Hh', data[4:8])
    return {
        'relay_request': bool(relay & 1), 'relay_output': bool(relay & 4), 'relay_ready': bool(relay & 8),
        'session_armed': bool(flags & 1), 'mask_active': bool(flags & 2),
        'overtemp_inhibit': bool(flags & 4), 'state3': bool(flags & 8),
        'lowtemp_alarm': lowtemp, 'lease_s': lease / 10, 'min_temp_c': temp / 10,
    }


def describe(status: dict) -> str:
    on = 'ON' if status['relay_output'] else 'off'
    parts = [f'heater {on}', f'lease {status["lease_s"]:.1f} s', f'min cell {status["min_temp_c"]:.1f} C']
    parts += [k for k in ('session_armed', 'mask_active', 'overtemp_inhibit') if status[k]]
    if not status['state3']:
        parts.append('not running (state != 3)')
    return ', '.join(parts)


def open_bus(interface: str, channel: str, bitrate: int):
    try:
        import can
    except ImportError:
        sys.exit('sending needs python-can: pip install python-can')
    return can.Bus(interface=interface, channel=channel, bitrate=bitrate)


def watch(bus, seconds: float) -> int:
    """Print every status broadcast for `seconds`; return how many arrived."""
    count, deadline = 0, time.monotonic() + seconds
    while (remaining := deadline - time.monotonic()) > 0:
        msg = bus.recv(timeout=remaining)
        if msg is not None and msg.is_extended_id and msg.arbitration_id == STATUS_ID:
            count += 1
            print(f'{time.strftime("%H:%M:%S")} 18FF7070#{bytes(msg.data).hex().upper()}  '
                  f'{describe(parse_status(bytes(msg.data)))}', flush=True)
    return count


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('command', nargs='?', choices=sorted(MAGIC))
    p.add_argument('seconds', type=float, nargs='?', default=0.0, help='lease in seconds; 0 cancels (default)')
    p.add_argument('--ticks', type=int, help='lease in 100 ms ticks, instead of seconds')
    p.add_argument('--interface', default='socketcan' if sys.platform.startswith('linux') else None,
                   help='python-can interface (default socketcan on Linux; required elsewhere)')
    p.add_argument('--channel', default='can1' if sys.platform.startswith('linux') else None,
                   help='adapter channel (default can1 on Linux)')
    p.add_argument('--bitrate', type=int, default=250000)
    p.add_argument('--source', type=lambda v: int(v, 0), default=SERVICE_TOOL, help='our address (default 0xF9)')
    p.add_argument('--dest', type=lambda v: int(v, 0), default=BMS_ADDRESS, help='BMS address (default 0x70)')
    p.add_argument('--repeat', type=int, default=1, help='send this many times')
    p.add_argument('--interval', type=float, default=1.0, help='seconds between repeats')
    p.add_argument('--watch', type=float, default=0.0, help='after sending, decode status frames this long')
    p.add_argument('--listen', type=float, help='send nothing; decode status frames this long')
    p.add_argument('--dry-run', action='store_true', help='print the frame, send nothing')
    args = p.parse_args(argv)

    if args.listen is None and args.command is None:
        p.error('give a command (heater or mask), or --listen SECONDS')
    if args.command:
        ticks = args.ticks if args.ticks is not None else round(args.seconds * 10)
        try:
            identifier, data = frame(args.command, ticks, args.source, args.dest)
        except ValueError as e:
            p.error(str(e))
        notation = f'{identifier:08X}#{data.hex().upper()}'
        if args.dry_run:
            print(notation)
            return 0
    if not args.interface or not args.channel:
        p.error('--interface and --channel are required on this platform (see --help)')

    bus = open_bus(args.interface, args.channel, args.bitrate)
    try:
        if args.listen is not None:
            return 0 if watch(bus, args.listen) else 1
        import can
        for n in range(args.repeat):
            if n:
                time.sleep(args.interval)
            bus.send(can.Message(arbitration_id=identifier, data=data, is_extended_id=True))
            print(f'{time.strftime("%H:%M:%S")} {notation}', flush=True)
        if args.watch and not watch(bus, args.watch):
            print(f'no status frame in {args.watch:.0f} s: is the BMS running a patched image, and is this the '
                  'RV-C bus?', file=sys.stderr)
            return 1
        return 0
    finally:
        bus.shutdown()


if __name__ == '__main__':
    sys.exit(main())
