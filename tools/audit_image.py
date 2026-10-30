#!/usr/bin/env python3
"""Show exactly what a patched image changes compared with stock, before you flash it.

    python tools/audit_image.py <stock.s19> <image.s19>

Every byte that differs from stock, or is added, is grouped into ranges and
named after the patch region it falls in. A range whose bytes match one of the
published recipes is reviewed; anything else (your own change, or a mistake)
is reported as 'unreviewed'. Unreviewed changes are shown, never refused.

Exit 1 only if the file can't be parsed or is over the size cap: 48,988
programmed bytes, the largest unpatched image Renogy has released for this
battery (137.4.1). The bootloader has accepted images up to that size.
"""
import contextlib
import hashlib
import io
import sys
import tempfile
from pathlib import Path
from typing import NamedTuple

import build_recipe
from s19 import parse
from to_global import global_address

MAX_IMAGE_BYTES = 48988

# Patch regions in global addresses (see docs/firmware-map.md for the mapping).
# Names follow the routines tools/make_patch.py assembles.
REGIONS = [
    (0x7F3780, 0x7F3800, 'uvcharge: UV mask, current cap and work-mode guard (FC:B780, added)'),
    (0x7F4AE7, 0x7F4AEB, 'heater: PIT hook, JSR tick (lease expiry)'),
    (0x7F4CD4, 0x7F4CDB, 'rvc: CAN1 receive splice into the shared dispatcher'),
    (0x7F4D78, 0x7F4E39, 'heater/rvc: compact CAN ID decode, lease tick, apply and relay write'),
    (0x7F500C, 0x7F5012, 'uvcharge: CAN4 0x100 work-mode splice'),
    (0x7F5581, 0x7F55CE, 'rvc: CAN1 command dispatcher in the inert CAN4 0x999 case, recovery helper'),
    (0x7F5738, 0x7F57E0, 'status: RV-C 0x18FF7070 status broadcast (added)'),
    (0x7F57E0, 0x7F5800, 'slew: charge current slew routine (added)'),
    (0x7F8008, 0x7F800C, 'status: main-loop splice, fast pass'),
    (0x7F8073, 0x7F8077, 'status: main-loop splice, 100 ms tick'),
    (0x7F8D33, 0x7F8D40, 'heater: relay output hook, JSR write'),
    (0x7F9139, 0x7F9164, 'heater: output routine and CAN1 wrapper'),
    (0x7F9537, 0x7F953A, 'cvl: soc_calc full-threshold splice'),
    (0x7FA8A4, 0x7FA8A7, 'cvl: register 0x138F limit splice'),
    (0x7FA96C, 0x7FA971, 'slew/uvcharge: 0x1390 store splice and current cap'),
    (0x7FA974, 0x7FA977, 'cvl: register 0x1391 splice'),
    (0x7FAC0A, 0x7FAC16, 'uvcharge: 1393/1394 alarm copy splice'),
    (0x7FACD3, 0x7FAD2B, 'mask: CAN1 alarm mask copy helpers and lease tick'),
    (0x7FAD5F, 0x7FAD6D, 'can1fix: busy release on the 4-word tail'),
    (0x7FAD8D, 0x7FADCE, 'mask/heater: mask toggle, busy return, heater publish'),
    (0x7FBFE0, 0x7FC000, 'cvl: cvl_get and cvl_cmp (FE:BFE0, added)'),
]


class Report(NamedTuple):
    recipe: str | None
    size: int
    size_ok: bool
    changed: list[tuple[int, int, str]]
    warnings: list[str] = []


def memory(path: Path) -> dict[int, int]:
    m = {}
    for r in parse(path):
        if r.kind in (1, 2):
            start = global_address(r.kind, r.address)
            for i, b in enumerate(r.data):
                m[start + i] = b
    return m


def programmed_bytes(path: Path) -> int:
    return sum(len(r.data) for r in parse(path) if r.kind in (1, 2))


def region(address: int) -> str | None:
    for start, end, label in REGIONS:
        if start <= address < end:
            return label
    return None


def reviewed_values(stock: Path) -> tuple[dict[int, set[int]], list[str]]:
    """Every byte value the published recipes place at each changed address.

    A recipe that tools/make_patch.py no longer reproduces (because it was edited)
    contributes nothing, and is named in the returned warnings.
    """
    values: dict[int, set[int]] = {}
    warnings: list[str] = []
    base = memory(stock)
    with tempfile.TemporaryDirectory() as d:
        for name in build_recipe.load_recipes():
            out = Path(d) / f'{name}.s19'
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    build_recipe.build(stock, name, out)
            except build_recipe.RecipeMismatch:
                warnings.append(f'tools/make_patch.py no longer reproduces recipe {name}; '
                                'its bytes are not treated as reviewed')
                continue
            for a, v in memory(out).items():
                if base.get(a) != v:
                    values.setdefault(a, set()).add(v)
    return values, warnings


def audit(stock: Path, image: Path) -> Report:
    digest = hashlib.sha256(Path(image).read_bytes()).hexdigest()
    recipe = next((r.name for r in build_recipe.load_recipes().values() if r.sha256 == digest), None)
    base, new = memory(stock), memory(image)
    reviewed, warnings = reviewed_values(stock)
    changed: list[tuple[int, int, str]] = []
    for a in sorted(set(base) | set(new)):
        if base.get(a) == new.get(a):
            continue
        label = region(a)
        if label is None or a not in new or new[a] not in reviewed.get(a, ()):
            label = 'unreviewed'
        if changed and changed[-1][1] == a and changed[-1][2] == label:
            changed[-1] = (changed[-1][0], a + 1, label)
        else:
            changed.append((a, a + 1, label))
    size = programmed_bytes(image)
    return Report(recipe, size, size <= MAX_IMAGE_BYTES, changed, warnings)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip())
        return 2
    try:
        report = audit(Path(sys.argv[1]), Path(sys.argv[2]))
    except ValueError as e:
        print(f'error: {e}')
        return 1
    for w in report.warnings:
        print(f'warning: {w}')
    print(f'{sys.argv[2]}: ' + (f'recipe {report.recipe}' if report.recipe else 'not a published recipe'))
    print(f'size: {report.size} programmed bytes, cap {MAX_IMAGE_BYTES}: ' + ('ok' if report.size_ok else 'OVER THE CAP'))
    for start, end, label in report.changed:
        print(f'  {start:06X}-{end - 1:06X} {end - start:4d} bytes  {label}')
    unreviewed = sum(e - s for s, e, label in report.changed if label == 'unreviewed')
    print(f'{len(report.changed)} changed ranges, {unreviewed} unreviewed bytes')
    return 0 if report.size_ok else 1


if __name__ == '__main__':
    sys.exit(main())
