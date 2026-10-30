#!/usr/bin/env python3
"""Rebuild one of the published patched images from your stock V137.4.5 file.

    python tools/build_recipe.py --list
    python tools/build_recipe.py <stock.s19> <recipe> [<out.s19>]

The output must match the recipe's published SHA-256 exactly; anything else
is an error, because the point of a recipe is the identical image. To build
your own variant, call tools/make_patch.py with your own flags, or change it.
"""
import hashlib
import json
import os
import sys
from pathlib import Path
from typing import NamedTuple

import make_patch
from verify_stock import check

RECIPES_JSON = Path(__file__).resolve().parents[1] / 'recipes' / 'recipes.json'


class Recipe(NamedTuple):
    name: str
    flags: list[str]
    sha256: str
    status: str
    summary: str


class RecipeMismatch(Exception):
    def __init__(self, name: str, expected: str, actual: str):
        super().__init__(f'{name}: built SHA-256 {actual}, expected {expected}')
        self.expected, self.actual = expected, actual


def load_recipes() -> dict[str, Recipe]:
    return {r['name']: Recipe(**r) for r in json.loads(RECIPES_JSON.read_text())}


def default_output(name: str) -> Path:
    return Path(f'V137.4.5_{name}_patch.s19')


def build(stock: Path, name: str, out: Path) -> str:
    recipes = load_recipes()
    if name not in recipes:
        raise KeyError(name)
    stock, out = Path(stock), Path(out)
    if out.resolve() == stock.resolve():
        raise ValueError('the output would overwrite your stock file; choose another file name')
    ok, reason = check(stock)
    if not ok:
        raise ValueError(reason)
    flags = recipes[name].flags
    # Build beside the output and move it into place only if it matches, so a
    # mismatch never leaves a flashable wrong image under the recipe's name.
    tmp = out.with_name(out.name + '.building')
    try:
        make_patch.build(stock, tmp,
                         with_cvl='--cvl' in flags,
                         with_slew=256 if '--slew256' in flags else 128 if '--slew' in flags else 0,
                         with_uv='--uvcharge' in flags,
                         with_diffmask='--diffmask' in flags,
                         cvl_max=make_patch.CVL_MAX_570 if '--cvl570' in flags else make_patch.CVL_MAX)
        actual = hashlib.sha256(tmp.read_bytes()).hexdigest()
        if actual != recipes[name].sha256:
            raise RecipeMismatch(name, recipes[name].sha256, actual)
        os.replace(tmp, out)
    finally:
        tmp.unlink(missing_ok=True)
    return actual


def list_recipes() -> str:
    lines = []
    for r in load_recipes().values():
        flags = ' '.join(r.flags) or '(no flags)'
        lines.append(f'{r.name:<11} {r.sha256[:8]}  {r.status:<22} {flags}\n            {r.summary}')
    return '\n'.join(lines)


def main(argv: list[str]) -> int:
    if argv == ['--list']:
        print(list_recipes())
        return 0
    if len(argv) not in (2, 3):
        print(__doc__.strip())
        return 2
    stock, name = Path(argv[0]), argv[1]
    if name not in load_recipes():
        print(f'unknown recipe {name!r}. Recipes:\n{list_recipes()}')
        return 2
    out = Path(argv[2]) if len(argv) == 3 else default_output(name)
    try:
        digest = build(stock, name, out)
    except (ValueError, RecipeMismatch) as e:
        print(f'error: {e}')
        return 1
    print(f'{out}: {name}, SHA-256 {digest} (matches the published recipe)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
