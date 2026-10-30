#!/usr/bin/env python3
"""Run the Ghidra emulator tests for a recipe, or for your own image, before you flash.

    python tools/run_emulator_tests.py --ghidra <Ghidra folder> --stock <stock.s19> --work <folder>
                                       (--recipe NAME | --all) [--image <own.s19>]

Builds the images a recipe's tests need into --work, converts them to the global
layout, imports the rvc_status image into a fresh project there, and runs each
test in ghidra/tests.json one at a time (read-only, no analysis). It prints PASS
or FAIL per test and writes <work>/<recipe>-<script>.log. Exit 1 if any test fails.

--image runs one recipe's tests against your own image instead of the recipe
build. The tests check exact bytes, so a changed image fails where it differs;
the log says where. A test marked image_flags in tests.json runs on its own
build and is skipped for --image.
"""
import argparse
import contextlib
import io
import json
import re
import sys
from pathlib import Path

import build_recipe
import ghidra_run
import make_patch
from to_global import convert

TESTS_JSON = ghidra_run.KIT_GHIDRA_SCRIPTS / 'tests.json'
PROJECT = 'emulator_tests'
HOST_PROGRAM = 'rvc_status_global.s19'


def load_tests() -> dict[str, list[dict]]:
    return {k: v for k, v in json.loads(TESTS_JSON.read_text()).items() if not k.startswith('_')}


def needed(tests: list[dict]) -> set[str]:
    return {m for t in tests for a in t['args'] for m in re.findall(r'\{base:(\w+)\}', a)}


class Images:
    """Global S19s in the work folder, built on first use."""

    def __init__(self, stock: Path, work: Path):
        self.stock, self.work, self.paths = stock, work, {}

    def stock_global(self) -> Path:
        out = self.work / 'stock_global.s19'
        if not out.exists():
            convert(self.stock, out)
        return out

    def recipe(self, name: str) -> Path:
        if name not in self.paths:
            banked = self.work / f'{name}.s19'
            with contextlib.redirect_stdout(io.StringIO()):
                build_recipe.build(self.stock, name, banked)
            self.paths[name] = self.work / f'{name}_global.s19'
            convert(banked, self.paths[name])
        return self.paths[name]

    def variant(self, flags: list[str]) -> Path:
        """A make_patch.py build with these flags (not a published recipe)."""
        name = 'variant' + ''.join(f.replace('--', '_') for f in flags)
        out = self.work / f'{name}_global.s19'
        if not out.exists():
            banked = self.work / f'{name}.s19'
            with contextlib.redirect_stdout(io.StringIO()):
                make_patch.build(self.stock, banked, with_cvl='--cvl' in flags,
                                 with_slew=256 if '--slew256' in flags else 128 if '--slew' in flags else 0,
                                 with_uv='--uvcharge' in flags, with_diffmask='--diffmask' in flags,
                                 cvl_max=make_patch.CVL_MAX_570 if '--cvl570' in flags else make_patch.CVL_MAX)
            convert(banked, out)
        return out

    def own(self, image: Path) -> Path:
        out = self.work / 'own_global.s19'
        convert(image, out)
        return out


def substitute(args: list[str], image: Path, images: Images) -> list[str]:
    def one(a: str) -> str:
        a = a.replace('{image}', str(image)).replace('{stock}', str(images.stock_global()))
        return re.sub(r'\{base:(\w+)\}', lambda m: str(images.recipe(m.group(1))), a)
    return [one(a) for a in args]


def passed(output: str, script: str, expect: str) -> bool:
    tag = f'{script}> '
    lines = [l for l in output.splitlines() if tag in l]
    if any('Abort due to' in l for l in output.splitlines()):
        return False
    return any(expect in l.split(tag, 1)[1] for l in lines)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--ghidra', type=Path, required=True)
    p.add_argument('--stock', type=Path, required=True)
    p.add_argument('--work', type=Path, required=True, help='folder for images, the project and logs')
    which = p.add_mutually_exclusive_group(required=True)
    which.add_argument('--recipe')
    which.add_argument('--all', action='store_true')
    p.add_argument('--image', type=Path, help="your own image, tested with --recipe's tests")
    args = p.parse_args()

    tests = load_tests()
    if args.recipe and args.recipe not in tests:
        print(f'unknown recipe {args.recipe!r}; recipes: {", ".join(tests)}')
        return 2
    if args.image and not args.recipe:
        print('--image needs --recipe, to choose which tests to run')
        return 2
    try:
        ghidra_run.find_headless(args.ghidra)
    except ghidra_run.GhidraNotFound as e:
        print(f'error: {e}')
        return 1
    warning = ghidra_run.version_warning(args.ghidra)
    if warning:
        print(warning)

    args.work.mkdir(parents=True, exist_ok=True)
    images = Images(args.stock, args.work)
    try:
        host = images.recipe('rvc_status')
        renamed = args.work / HOST_PROGRAM
        if host != renamed:
            renamed.write_bytes(host.read_bytes())
        result = ghidra_run.run(args.ghidra, args.work, PROJECT,
                                ['-import', str(renamed), '-overwrite', '-noanalysis',
                                 '-loader', 'MotorolaHexLoader', '-processor', 'HCS-12X:BE:24:default',
                                 '-cspec', 'default'], args.work / 'import.log')
    except (ValueError, OSError, ghidra_run.ProjectLocked, build_recipe.RecipeMismatch) as e:
        print(f'error: {e}')
        return 1
    if result.returncode != 0:
        print(f'error: import failed; see {args.work / "import.log"}')
        return 1

    failures, untested = 0, []
    try:
        for recipe in [args.recipe] if args.recipe else list(tests):
            image = images.own(args.image) if args.image else images.recipe(recipe)
            for test in tests[recipe]:
                script = test['script']
                if 'image_flags' in test:
                    if args.image:
                        untested.append(f'{script} ({recipe}): it runs only on {test["note"]}')
                        print(f'NOT TESTED {script} ({recipe}): it runs only on {test["note"]}, not on your image')
                        continue
                    target = images.variant(test['image_flags'])
                else:
                    target = image
                log = args.work / f'{recipe}-{Path(script).stem}.log'
                run = ghidra_run.run(args.ghidra, args.work, PROJECT,
                                     ['-process', HOST_PROGRAM, '-readOnly', '-noanalysis',
                                      '-scriptPath', str(ghidra_run.KIT_GHIDRA_SCRIPTS),
                                      '-postScript', script, *substitute(test['args'], target, images)], log)
                ok = passed(run.stdout + run.stderr, script, test['expect'])
                failures += not ok
                print(f'{"PASS" if ok else "FAIL"} {script} ({recipe}){"" if ok else f"; see {log}"}', flush=True)
    except (ValueError, OSError, ghidra_run.ProjectLocked, build_recipe.RecipeMismatch) as e:
        print(f'error: {e}')
        return 1
    if untested:
        print(f'{len(untested)} test(s) NOT run on your image; write a harness for those features '
              '(docs/emulator-testing.md)')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
