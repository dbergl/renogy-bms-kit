#!/usr/bin/env python3
"""Check that a file is Renogy's stock V137.4.5 firmware, byte for byte.

    python tools/verify_stock.py "path/to/V137.4.5[20240719a].s19"

Every recipe in this kit starts from that exact file. Exit 0 if it matches.
"""
import hashlib
import sys
from pathlib import Path

from s19 import STOCK_SHA256


def check(path: Path) -> tuple[bool, str]:
    path = Path(path)
    if not path.is_file():
        return False, f'{path}: not found'
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() == STOCK_SHA256:
        return True, 'stock V137.4.5'
    if b'\r\n' not in data and hashlib.sha256(data.replace(b'\n', b'\r\n')).hexdigest() == STOCK_SHA256:
        return False, (f'{path}: the line endings were changed from CRLF to LF (by an unzip tool or git). '
                       'Re-extract the file from Renogy\'s package, or convert it back to CRLF.')
    if b'\r\n' not in data:
        return False, (f'{path}: not the stock V137.4.5 image, and its line endings are LF while stock uses '
                       'CRLF. Re-extract it from Renogy\'s package.')
    return False, f'{path}: not the stock V137.4.5 image (SHA-256 {hashlib.sha256(data).hexdigest()})'


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip())
        return 2
    ok, reason = check(Path(sys.argv[1]))
    print(reason)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
