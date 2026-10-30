import hashlib
import json
import shutil
import subprocess
import sys

import pytest

from conftest import ROOT
import build_recipe

TOOL = ROOT / 'tools' / 'build_recipe.py'
RECIPES = json.loads((ROOT / 'recipes' / 'recipes.json').read_text())
NAMES = [r['name'] for r in RECIPES]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_four_recipes():
    assert NAMES == ['rvc_status', 'cvl', 'slew256', 'uvcharge']
    assert set(build_recipe.load_recipes()) == set(NAMES)


@pytest.mark.parametrize('recipe', RECIPES, ids=NAMES)
def test_recipe_rebuilds_exactly(stock, tmp_path, recipe):
    out = tmp_path / f'{recipe["name"]}.s19'
    assert build_recipe.build(stock, recipe['name'], out) == recipe['sha256']
    assert sha256(out) == recipe['sha256']


def test_list_shows_names_and_status():
    result = subprocess.run([sys.executable, str(TOOL), '--list'], capture_output=True, text=True)
    assert result.returncode == 0
    for r in RECIPES:
        assert r['name'] in result.stdout and r['status'] in result.stdout


def test_refuses_to_overwrite_stock(stock, tmp_path):
    copy = tmp_path / 'stock.s19'
    shutil.copy(stock, copy)
    before = sha256(copy)
    with pytest.raises(ValueError):
        build_recipe.build(copy, 'cvl', copy)
    assert sha256(copy) == before


def test_unknown_recipe_lists_valid_names(stock, tmp_path):
    result = subprocess.run([sys.executable, str(TOOL), str(stock), 'nope', str(tmp_path / 'x.s19')],
                            capture_output=True, text=True)
    assert result.returncode == 2
    for name in NAMES:
        assert name in result.stdout + result.stderr


def test_non_stock_input_rejected_before_building(stock, tmp_path):
    bad = tmp_path / 'bad.s19'
    bad.write_bytes(stock.read_bytes().replace(b'\r\n', b'\n'))
    out = tmp_path / 'out.s19'
    with pytest.raises(ValueError, match='line endings'):
        build_recipe.build(bad, 'cvl', out)
    assert not out.exists()


def test_default_output_name(stock, tmp_path):
    result = subprocess.run([sys.executable, str(TOOL), str(stock), 'cvl'], cwd=tmp_path,
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert sha256(tmp_path / 'V137.4.5_cvl_patch.s19') == RECIPES[1]['sha256']


def test_mismatch_leaves_no_image(stock, tmp_path, monkeypatch):
    real = build_recipe.load_recipes
    monkeypatch.setattr(build_recipe, 'load_recipes',
                        lambda: {n: r._replace(sha256='0' * 64) for n, r in real().items()})
    out = tmp_path / 'V137.4.5_cvl_patch.s19'
    out.write_text('previous good image')
    with pytest.raises(build_recipe.RecipeMismatch):
        build_recipe.build(stock, 'cvl', out)
    assert out.read_text() == 'previous good image'
    assert list(tmp_path.iterdir()) == [out]


def test_make_patch_refuses_to_overwrite_stock(stock, tmp_path):
    copy = tmp_path / 'stock.s19'
    shutil.copy(stock, copy)
    before = sha256(copy)
    result = subprocess.run([sys.executable, str(ROOT / 'tools' / 'make_patch.py'), str(copy), str(copy), '--cvl'],
                            capture_output=True, text=True)
    assert result.returncode != 0 and 'Traceback' not in result.stderr
    assert sha256(copy) == before


def test_make_patch_usage_and_unknown_flag(tmp_path):
    tool = str(ROOT / 'tools' / 'make_patch.py')
    for argv in ([], ['--help'], ['a.s19', 'b.s19', '--cvl57']):
        result = subprocess.run([sys.executable, tool, *argv], capture_output=True, text=True)
        assert 'Traceback' not in result.stderr, argv
        assert 'usage' in (result.stdout + result.stderr).lower(), argv
    assert subprocess.run([sys.executable, tool, 'a.s19', 'b.s19', '--cvl57'], capture_output=True).returncode == 2
