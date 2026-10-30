"""Motorola S-record helpers shared by the kit's tools."""
from pathlib import Path
from typing import NamedTuple

# Renogy's V137.4.5[20240719a].s19 exactly as shipped (CRLF line endings).
STOCK_SHA256 = 'c589c76ae339e9a869f2e27b79ce2e0016b9eb38b469f8cf91ed74d838ee1932'


class Record(NamedTuple):
    kind: int      # 0-9, the digit after 'S'
    address: int
    data: bytes


ADDRESS_BYTES = {0: 2, 1: 2, 2: 3, 3: 4, 5: 2, 6: 3, 7: 4, 8: 3, 9: 2}


def parse_line(line: str, where: str = '') -> Record | None:
    """One record, checksum verified; None for a blank line."""
    line = line.strip()
    if not line:
        return None
    if len(line) < 4 or line[0] != 'S' or not line[1].isdigit():
        raise ValueError(f'{where}: not an S-record')
    kind = int(line[1])
    body = bytes.fromhex(line[2:])
    if body[0] != len(body) - 1 or (sum(body) & 0xFF) != 0xFF:
        raise ValueError(f'{where}: bad length or checksum')
    width = ADDRESS_BYTES[kind]
    return Record(kind, int.from_bytes(body[1:1 + width], 'big'), body[1 + width:-1])


def parse(path: Path) -> list[Record]:
    """Every record in the file, checksums verified."""
    records = []
    for number, line in enumerate(Path(path).read_text().splitlines(), 1):
        r = parse_line(line, f'{path}:{number}')
        if r:
            records.append(r)
    return records


def format_record(kind: int, address: int, data: bytes) -> str:
    width = ADDRESS_BYTES[kind]
    body = bytes([len(data) + width + 1]) + address.to_bytes(width, 'big') + data
    return f'S{kind}' + body.hex().upper() + f'{~sum(body) & 0xFF:02X}'
