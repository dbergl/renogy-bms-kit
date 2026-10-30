import subprocess
import sys

import pytest

from conftest import ROOT

TOOL = ROOT / 'tools' / 'run_emulator_tests.py'


def run(ghidra_home, stock, work, *args):
    return subprocess.run([sys.executable, str(TOOL), '--ghidra', str(ghidra_home), '--stock', str(stock),
                           '--work', str(work), *args], capture_output=True, text=True)


def test_unknown_recipe(tmp_path):
    result = subprocess.run([sys.executable, str(TOOL), '--ghidra', str(tmp_path), '--stock', 'x', '--work',
                             str(tmp_path), '--recipe', 'nope'], capture_output=True, text=True)
    assert result.returncode == 2 and 'rvc_status' in result.stdout + result.stderr


@pytest.mark.ghidra
@pytest.mark.parametrize('recipe', ['rvc_status', 'cvl', 'slew256', 'uvcharge'])
def test_recipe_acceptance(ghidra_home, stock, tmp_path, recipe):
    result = run(ghidra_home, stock, tmp_path, '--recipe', recipe)
    assert result.returncode == 0, result.stdout[-4000:] + result.stderr[-2000:]
    assert 'FAIL' not in result.stdout
    assert result.stdout.count('PASS ') >= 1


@pytest.mark.ghidra
def test_own_image_that_differs_fails(ghidra_home, stock, tmp_path):
    # The cvl test's image check must reject an image that is not the cvl recipe.
    import build_recipe
    own = tmp_path / 'own.s19'
    build_recipe.build(stock, 'slew256', own)
    result = run(ghidra_home, stock, tmp_path / 'work', '--recipe', 'cvl', '--image', str(own))
    assert result.returncode == 1 and 'FAIL CvlSettingTest.java' in result.stdout


@pytest.mark.ghidra
def test_own_image_reports_untested_features(ghidra_home, stock, tmp_path):
    import build_recipe
    own = tmp_path / 'own.s19'
    build_recipe.build(stock, 'uvcharge', own)
    result = run(ghidra_home, stock, tmp_path / 'work', '--recipe', 'uvcharge', '--image', str(own))
    assert 'NOT TESTED UvChargeTest.java' in result.stdout
    assert 'NOT run on your image' in result.stdout
