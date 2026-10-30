#!/usr/bin/env python3
"""Convert a banked CodeWarrior S19 to the global layout Ghidra's HCS12X loader uses.

    python tools/to_global.py <in.s19> <out.s19>

Mapping:
- S1 addresses 0x4000-0xFFFF go to 0x7F0000 + address (unpaged page FD, the
  default PPAGE=FE window, and page FF).
- S2 page PP, local 0x8000-0xBFFF, goes to 0x400000 + PP*0x4000 + (local - 0x8000).

The output is S3 records plus an S7 terminator, LF line endings.
"""
import sys
from pathlib import Path

from s19 import format_record, parse


def global_address(kind: int, address: int) -> int:
    if kind == 1:
        return address if address < 0x4000 else 0x7F0000 + address
    page, local = address >> 16, address & 0xFFFF
    if not 0x8000 <= local < 0xC000:
        raise ValueError(f'paged address {address:06X} is outside the 0x8000-0xBFFF window')
    return 0x400000 + page * 0x4000 + (local - 0x8000)


def convert(src: Path, dst: Path) -> int:
    """Write the global S19 and return the number of data bytes converted."""
    src, dst = Path(src), Path(dst)
    if dst.resolve() == src.resolve():
        raise ValueError('the output would overwrite the input; choose another file name')
    lines, count = [], 0
    for r in parse(src):
        if r.kind in (1, 2):
            lines.append(format_record(3, global_address(r.kind, r.address), r.data))
            count += len(r.data)
    lines.append(format_record(7, 0, b''))
    dst.write_bytes(('\n'.join(lines) + '\n').encode('ascii'))
    return count


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip())
        return 2
    try:
        count = convert(Path(sys.argv[1]), Path(sys.argv[2]))
    except ValueError as e:
        print(f'error: {e}')
        return 1
    print(f'{sys.argv[2]}: {count} bytes')
    return 0


if __name__ == '__main__':
    sys.exit(main())
