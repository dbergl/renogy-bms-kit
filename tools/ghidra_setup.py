#!/usr/bin/env python3
"""Create an annotated Ghidra project from your stock V137.4.5 file in one step.

    python tools/ghidra_setup.py --ghidra <Ghidra folder> --stock <V137.4.5[20240719a].s19>
                                 --project <folder> [--name renogy_bms]

1. Checks the stock file.
2. Converts it to the global layout (<folder>/stock_global.s19).
3. Imports it as HCS12X, with the paging setup (SetupHCS12.java) before analysis,
   then FixupDisasm.java and ApplyMarkup.java (names, comments, types) after it.

Run it again at any time: the program is replaced, not duplicated. Close Ghidra
first. The full analyzer output goes to <folder>/<name>-setup.log.
"""
import argparse
import re
import sys
from pathlib import Path

import ghidra_run
from to_global import convert
from verify_stock import check

MARKUP_DIR = Path(__file__).resolve().parents[1] / 'markup'
IMPORT = ['-loader', 'MotorolaHexLoader', '-processor', 'HCS-12X:BE:24:default', '-cspec', 'default']


def headless_args(global_s19: Path) -> list[str]:
    return ['-import', str(global_s19), '-overwrite', *IMPORT,
            '-scriptPath', str(ghidra_run.KIT_GHIDRA_SCRIPTS),
            '-preScript', 'SetupHCS12.java',
            '-postScript', 'FixupDisasm.java',
            '-postScript', 'ApplyMarkup.java', str(MARKUP_DIR)]


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--ghidra', type=Path, required=True, help='Ghidra install folder')
    p.add_argument('--stock', type=Path, required=True, help='your stock V137.4.5[20240719a].s19')
    p.add_argument('--project', type=Path, required=True, help='folder for the Ghidra project')
    p.add_argument('--name', default='renogy_bms', help='project name (default renogy_bms)')
    args = p.parse_args()
    try:
        ghidra_run.find_headless(args.ghidra)
    except ghidra_run.GhidraNotFound as e:
        print(f'error: {e}')
        return 1
    warning = ghidra_run.version_warning(args.ghidra)
    if warning:
        print(warning)
    ok, reason = check(args.stock)
    if not ok:
        print(f'error: {reason}')
        return 1
    lock = args.project / f'{args.name}.lock'
    if lock.exists():
        print(f'error: {lock} exists: Ghidra is open on this project. Close it and run again.')
        return 1
    args.project.mkdir(parents=True, exist_ok=True)
    global_s19 = args.project / 'stock_global.s19'
    convert(args.stock, global_s19)
    log = args.project / f'{args.name}-setup.log'
    print(f'Importing and analyzing (several minutes); log: {log}')
    try:
        result = ghidra_run.run(args.ghidra, args.project, args.name, headless_args(global_s19), log)
    except ghidra_run.ProjectLocked as e:
        print(f'error: {e}')
        return 1
    output = result.stdout + result.stderr
    for line in output.splitlines():
        if 'MARKUP ' in line:
            print(re.sub(r'^.*?(MARKUP .*?)( \(GhidraScript\))?\s*$', r'\1', line))
    m = re.search(r'MARKUP functions=\d+ labels=\d+ structs=\d+ uses=\d+ errors=(\d+)', output)
    if result.returncode != 0 or not m:
        print(f'error: the analyzer did not finish (exit {result.returncode}); see {log}')
        return 1
    if int(m.group(1)):
        print(f'error: markup errors above; see {log}')
        return 1
    print(f'Done. Open Ghidra, open the project {args.project / args.name}.gpr, then stock_global.s19.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
