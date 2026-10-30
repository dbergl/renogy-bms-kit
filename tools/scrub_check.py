#!/usr/bin/env python3
"""Refuse to publish personal information or firmware.

Scans every file git would commit (tracked plus untracked, minus ignored) and
reports home paths, email addresses other than the allowed ones, S-record
lines, binary files, file types that hold firmware or vendor output, and any
entry of the private blocklist. The blocklist file (.scrub-blocklist, one
entry per line, # comments) is git-ignored so the names it guards are never
published.

    python tools/scrub_check.py [--root .] [--blocklist .scrub-blocklist]

Exit 0 when clean, 1 with one line per finding otherwise.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import NamedTuple

ALLOWED_EMAILS = {'dbergl@hotmail.com', 'noreply@anthropic.com'}
BLOCKLIST_NAME = '.scrub-blocklist'
FORBIDDEN_SUFFIXES = {'.s19', '.s28', '.s37', '.srec', '.hex', '.abs', '.bin', '.dfu',
                      '.gpr', '.rep', '.gzf', '.gdt', '.exe', '.dll', '.sys', '.zip'}
FORBIDDEN_NAMES = {'decompiled.c', 'listing.asm'}

# Built from pieces so this file does not match itself.
_U = 'Users'
HOME = re.compile('/' + r'home/[^/\s]+' + '|/' + _U + r'/[^/\s]+' + r'|[A-Za-z]:\\' + _U + r'\\', re.IGNORECASE)
EMAIL = re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
SRECORD = re.compile(r'^S[0-9][0-9A-Fa-f]{8,}$')


class Finding(NamedTuple):
    path: str
    line: int
    reason: str


def files(root: Path) -> list[str]:
    out = subprocess.run(['git', '-C', str(root), 'ls-files', '--cached', '--others', '--exclude-standard'],
                         capture_output=True, text=True, check=True).stdout
    return sorted(set(n for n in out.splitlines() if n and n != BLOCKLIST_NAME))


def scan(root: Path, blocklist: list[str]) -> list[Finding]:
    root = Path(root)
    blocked = [b.lower() for b in blocklist if b]
    findings = []
    for name in files(root):
        path = root / name
        if not path.is_file():
            continue
        if path.suffix.lower() in FORBIDDEN_SUFFIXES or path.name.lower() in FORBIDDEN_NAMES \
                or path.name.lower().endswith('.lock'):
            findings.append(Finding(name, 0, 'forbidden file'))
            continue
        data = path.read_bytes()
        if b'\x00' in data:
            findings.append(Finding(name, 0, 'binary'))
            continue
        for number, line in enumerate(data.decode('utf-8', errors='replace').splitlines(), 1):
            if HOME.search(line):
                findings.append(Finding(name, number, 'home path'))
            if any(e.lower() not in ALLOWED_EMAILS for e in EMAIL.findall(line)):
                findings.append(Finding(name, number, 'email'))
            if SRECORD.match(line.strip()):
                findings.append(Finding(name, number, 's-record'))
            lowered = line.lower()
            if any(b in lowered for b in blocked):
                findings.append(Finding(name, number, 'blocklist'))
    return findings


def read_blocklist(path: Path) -> list[str]:
    if not path.is_file():
        return []
    entries = (line.split('#', 1)[0].strip() for line in path.read_text().splitlines())
    return [e for e in entries if e]


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--root', type=Path, default=Path('.'))
    p.add_argument('--blocklist', type=Path, help=f'default: <root>/{BLOCKLIST_NAME}')
    args = p.parse_args()
    blocklist = read_blocklist(args.blocklist or args.root / BLOCKLIST_NAME)
    findings = scan(args.root, blocklist)
    for f in findings:
        print(f'{f.path}:{f.line}: {f.reason}')
    return 1 if findings else 0


if __name__ == '__main__':
    sys.exit(main())
