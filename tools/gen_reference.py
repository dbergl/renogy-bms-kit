#!/usr/bin/env python3
"""Render docs/function-reference.md from the Ghidra markup in markup/.

    python tools/gen_reference.py          # rewrite the reference
    python tools/gen_reference.py --check  # exit 1 if it is out of date

Functions are grouped by the part of their name before the first underscore
(alarm, heater, can, ...); single-member groups go under "other".
"""
import sys
from collections import defaultdict
from pathlib import Path

import markup

OUT = Path(__file__).resolve().parents[1] / 'docs' / 'function-reference.md'
HEADER = """# Function and variable reference

Every function and variable named in the kit's Ghidra markup for stock V137.4.5, with what it does. The same
text appears in Ghidra as plate comments once [`tools/ghidra_setup.py`](../tools/ghidra_setup.py) has applied
the markup. Addresses are Ghidra global addresses (see [the firmware map](firmware-map.md)): flash `7Fxxxx`, RAM
`0FExxx` (RAM `0x2350` is `0FE350`), I/O registers below `000800`. Summaries starting "Likely:" are inferred, not
established.

Generated from `markup/functions.tsv` and `markup/labels.tsv` by `tools/gen_reference.py`; edit those, not this file.
"""


def cell(text: str) -> str:
    return text.replace('|', '\\|').replace('\\n', '<br>')


def area(name: str) -> str:
    return name.split('_', 1)[0] if '_' in name else name


def render(directory: Path = markup.MARKUP) -> str:
    functions = markup.read('functions.tsv', directory)
    labels = markup.read('labels.tsv', directory)
    groups = defaultdict(list)
    for f in functions:
        groups[area(f['name'])].append(f)
    other = [f for g in list(groups) if len(groups[g]) == 1 for f in groups.pop(g)]
    out = [HEADER]
    for g in sorted(groups) + (['other'] if other else []):
        rows = groups[g] if g != 'other' else other
        out.append(f'\n## {g}\n\n| Address | Function | What it does |\n|---|---|---|')
        for f in sorted(rows, key=lambda r: r['address']):
            details = f'<br><br>{cell(f["details"])}' if f['details'] else ''
            out.append(f'| `{f["address"].upper()}` | `{f["name"]}` | {cell(f["summary"])}{details} |')
    out.append('\n## Variables and registers\n\n| Address | Name | Kind | What it holds |\n|---|---|---|---|')
    for l in sorted(labels, key=lambda r: r['address']):
        out.append(f'| `{l["address"].upper()}` | `{l["name"]}` | {l["kind"]} | {cell(l["summary"])} |')
    return '\n'.join(out) + '\n'


def main() -> int:
    text = render()
    if sys.argv[1:] == ['--check']:
        current = OUT.read_text() if OUT.exists() else ''
        if current != text:
            print(f'{OUT} is out of date: run python tools/gen_reference.py')
            return 1
        return 0
    OUT.write_text(text)
    print(f'wrote {OUT}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
