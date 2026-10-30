#!/usr/bin/env python3
"""Talk to the APS-500 (Wakespeed WS500) over its USB virtual COM port.

The regulator streams AST; status lines about once a second. Commands are sent
as "$XXX:" and answered with AOK; plus whatever status strings apply.

Read-only commands, safe at any time:

    $RAS:    request all status strings (CPE; CST; ENG; NPC; SCV; SST;)
    $RCP:0   request the currently selected charge profile
    $RLF:    request the last fault

Anything starting $SC, $CP, $CNG, $MSR, $RBT, $DEP, $CCN or $CDD WRITES
CONFIGURATION or reboots the regulator. This script refuses those unless
--allow-write is given, because the regulator controls live alternator charging.

    python tools/ws500_serial.py --port COM5                  # watch AST; for 6 s (Windows)
    python tools/ws500_serial.py --seconds 20 --ast-only      # Linux: the by-id port is found itself
    python tools/ws500_serial.py --port COM5 '$RAS:' '$RLF:'  # query

Needs pyserial (pip install pyserial). The port is 115200 baud.
"""
import argparse
import glob
import sys
import time

BAUD = 115200
WRITE_PREFIXES = ('$SC', '$CP', '$CNG', '$MSR', '$RBT', '$DEP', '$CCN', '$CDD')


def looks_like_write(cmd: str) -> bool:
    up = cmd.strip().upper()
    return any(up.startswith(p) for p in WRITE_PREFIXES)


def default_port() -> str | None:
    """On Linux the regulator appears under /dev/serial/by-id; elsewhere the user names the port."""
    if not sys.platform.startswith('linux'):
        return None
    found = sorted(glob.glob('/dev/serial/by-id/usb-Wakespeed_WS500_Virtual_ComPort_*'))
    return found[0] if found else '/dev/ttyACM0'


def run(port: str, cmds: list[str], seconds: float, gap: float, ast_only: bool, sink) -> None:
    try:
        import serial
    except ImportError:
        sys.exit('this tool needs pyserial: pip install pyserial')
    with serial.Serial(port, BAUD, timeout=0.2) as link:
        deadline = time.time() + seconds
        next_send, sent, pending = time.time() + 1.0, 0, b''
        while time.time() < deadline or sent < len(cmds):
            pending += link.read(4096)
            while b'\n' in pending:
                raw, pending = pending.split(b'\n', 1)
                line = raw.decode('ascii', 'replace').strip().strip('.')
                if line and not (ast_only and not line.startswith('AST;')):
                    sink('%.2f %s' % (time.time(), line))
            if sent < len(cmds) and time.time() >= next_send:
                link.write((cmds[sent] + '\r\n').encode())
                sent += 1
                next_send = time.time() + gap
                deadline = max(deadline, next_send + gap)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('cmds', nargs='*', help='commands to send, e.g. $RAS:')
    ap.add_argument('--port', default=default_port(), help='serial port, e.g. COM5 (found automatically on Linux)')
    ap.add_argument('--seconds', type=float, default=6.0, help='how long to listen (default 6)')
    ap.add_argument('--gap', type=float, default=2.5, help='seconds between commands (default 2.5)')
    ap.add_argument('--ast-only', action='store_true', help='keep only AST; status lines')
    ap.add_argument('--out', help='write to this file as well as stdout')
    ap.add_argument('--allow-write', action='store_true', help='permit configuration-changing commands')
    args = ap.parse_args()

    for c in args.cmds:
        if looks_like_write(c) and not args.allow_write:
            print(f'refusing to send {c!r} without --allow-write: it changes regulator configuration or reboots it')
            return 2
    if not args.port:
        ap.error('--port is required on this platform (e.g. --port COM5)')

    fh = open(args.out, 'w') if args.out else None

    def sink(line):
        print(line, flush=True)
        if fh:
            fh.write(line + '\n')
            fh.flush()
    try:
        run(args.port, args.cmds, args.seconds, args.gap, args.ast_only, sink)
    except OSError as e:
        print(f'could not open {args.port}: {e}')
        return 1
    finally:
        if fh:
            fh.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
