#!/usr/bin/env python3
"""Build a second Ghidra install whose HCS12 decompiler reads this firmware's branches correctly.

    python tools/fix_ghidra.py --ghidra <Ghidra install> --out <new folder>

Ghidra 12.x's HCS12 language (HCS_HC12.sinc) gets three instruction families wrong, and every
decompiled condition that uses them comes out wrong:

- BRSET branches when any masked bit is clear; the CPU branches when all are set.
- BGE / LBGE branch on N xor V = 1, which is BLT; the CPU branches on N xor V = 0.
- CPED / CPES / CPEX / CPEY set Z from the high word alone and ignore the borrow in C and V; the
  CPU keeps Z sticky across a multi-word compare and includes the borrow. This breaks the
  32-bit compare helper rt_lcmp.

The fourth problem, helpers that return their result in the condition codes, needs a
decompile-time fix: run ghidra/ExportFixedDecomp.java under the new install (see
docs/ghidra-walkthrough.md).

The new install is a copy of the old one (hard links where the file system allows, so it costs
almost no space); only the HCS12 language files are rewritten. Your original install is not
touched, and the emulator tests keep using it: their harness corrects the stock model itself.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

LANG = Path('Ghidra') / 'Processors' / 'HCS12' / 'data' / 'languages'
SPECS = ('HC12', 'HCS12', 'HCS12X')

_BRSET = re.compile(r'(:BRSET [^\n]*\n\{\n\tresult:1 = ~[^\n]*\n\tif \(result )!=( 0\) goto rel8;)')
_CPE = re.compile(
    r'(:CPE[DSXY] [^\n]*\n\{ ?\n\top1:2 = [^\n]*;\n)'
    r'\ttmp:2 = (\w+) - \(op1 \+ zext\(\$\(C\)\)\);\n'
    r'\t\$\(Z\) = \(tmp == 0\);\n'
    r'\t\$\(N\) = \(tmp s< 0\);\n'
    r'\t\$\(C\) = \(op1 > \w+\);\n'
    r'\tV_CMP_flag\(\w+, op1\);\n')


class PatchError(Exception):
    pass


def _cpe_body(m: re.Match) -> str:
    r = m.group(2)
    return (f'{m.group(1)}'
            f'\tcin:2 = zext($(C));\n'
            f'\ttmp:2 = {r} - (op1 + cin);\n'
            f'\t# HCS12X CPE*: Z is sticky across the multi-word compare; C and V include the borrow-in\n'
            f'\t$(Z) = $(Z) & (tmp == 0);\n'
            f'\t$(N) = (tmp s< 0);\n'
            f'\tsub:4 = zext(op1) + zext(cin);\n'
            f'\treg:4 = zext({r});\n'
            f'\t$(C) = sub > reg;\n'
            f'\t$(V) = (({r} ^ op1) & ({r} ^ tmp)) s< 0;\n')


def patch_sinc(text: str) -> str:
    """Return HCS_HC12.sinc with the three fixes; raise PatchError if the text isn't the expected one."""
    text, n = _BRSET.subn(r'\1==\2', text)
    if n != 3:
        raise PatchError(f'expected 3 BRSET constructors, matched {n} (already patched, or another Ghidra version)')
    for op, rel in (('BGE', 'rel8'), ('LBGE', 'rel16')):
        head = re.search(rf'^:{op} {rel}\b[^\n]*\n\{{\n', text, re.M)
        old = f'\tif (($(N) ^ $(V)) == 1) goto {rel};\n'
        if not head or not text.startswith(old, head.end()):
            raise PatchError(f'{op} constructor not in the expected form')
        text = text[:head.end()] + old.replace('== 1', '== 0') + text[head.end() + len(old):]
    text, n = _CPE.subn(_cpe_body, text)
    if n != 16:
        raise PatchError(f'expected 16 CPED/CPES/CPEX/CPEY constructors, matched {n}')
    return text


def copy_install(src: Path, dst: Path) -> None:
    def link_or_copy(s, d):
        try:
            os.link(s, d)
        except OSError:
            shutil.copy2(s, d)
    shutil.copytree(src, dst, copy_function=link_or_copy, symlinks=True)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--ghidra', type=Path, required=True, help='your Ghidra install folder (left unchanged)')
    p.add_argument('--out', type=Path, required=True, help='folder for the fixed copy (must not exist)')
    args = p.parse_args()

    src_lang = args.ghidra / LANG
    if not (src_lang / 'HCS_HC12.sinc').is_file():
        print(f'error: no {src_lang / "HCS_HC12.sinc"}; --ghidra must be the Ghidra install folder', file=sys.stderr)
        return 1
    if args.out.exists():
        print(f'error: {args.out} already exists', file=sys.stderr)
        return 1
    try:
        fixed = patch_sinc((src_lang / 'HCS_HC12.sinc').read_text())
    except PatchError as e:
        print(f'error: {e}', file=sys.stderr)
        return 1

    print(f'copying {args.ghidra} -> {args.out} (hard links where possible)')
    copy_install(args.ghidra, args.out)
    lang = args.out / LANG
    # Replace, never write through, so a hard-linked original is not modified.
    for name in ['HCS_HC12.sinc'] + [f'{s}.sla' for s in SPECS]:
        (lang / name).unlink(missing_ok=True)
    (lang / 'HCS_HC12.sinc').write_text(fixed)
    sleigh = args.out / 'support' / ('sleigh.bat' if os.name == 'nt' else 'sleigh')
    for spec in SPECS:
        result = subprocess.run([str(sleigh), f'{spec}.slaspec', f'{spec}.sla'], cwd=lang,
                                capture_output=True, text=True, errors='replace')
        if result.returncode != 0 or not (lang / f'{spec}.sla').is_file():
            print(result.stdout + result.stderr, file=sys.stderr)
            print(f'error: sleigh failed on {spec}.slaspec', file=sys.stderr)
            return 1
    print(f'Done. Use {args.out} for decompiling (ghidra/ExportFixedDecomp.java); keep the original for the '
          f'emulator tests.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
