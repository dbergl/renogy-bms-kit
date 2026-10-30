import re
import shutil
import subprocess
import sys

import pytest

from conftest import ROOT
import markup

TOOL = ROOT / 'tools' / 'ghidra_setup.py'
FUNCTIONS = len(markup.read('functions.tsv'))
LABELS = len(markup.read('labels.tsv'))


def run_setup(ghidra_home, stock, project, name='renogy_bms'):
    return subprocess.run([sys.executable, str(TOOL), '--ghidra', str(ghidra_home), '--stock', str(stock),
                           '--project', str(project), '--name', name], capture_output=True, text=True)


def markup_counts(output):
    m = re.search(r'MARKUP functions=(\d+) labels=(\d+) structs=(\d+) uses=(\d+) errors=(\d+)', output)
    assert m, output[-3000:]
    return tuple(int(g) for g in m.groups())


def test_missing_ghidra_names_expected_path(tmp_path):
    result = subprocess.run([sys.executable, str(TOOL), '--ghidra', str(tmp_path / 'nonexistent'),
                             '--stock', 'x.s19', '--project', str(tmp_path)], capture_output=True, text=True)
    assert result.returncode == 1
    assert 'support/analyzeHeadless' in result.stdout + result.stderr


def test_locked_project_refused(ghidra_home, stock, tmp_path):
    (tmp_path / 'renogy_bms.lock').write_text('')
    result = run_setup(ghidra_home, stock, tmp_path)
    assert result.returncode == 1
    assert 'Ghidra is open' in result.stdout + result.stderr


@pytest.mark.ghidra
def test_setup_applies_all_markup_twice(ghidra_home, stock, tmp_path):
    folder = tmp_path / 'My Ghidra [kit] projects'
    folder.mkdir()
    copy = folder / 'V137.4.5[20240719a].s19'
    shutil.copy(stock, copy)
    first = run_setup(ghidra_home, copy, folder)
    assert first.returncode == 0, first.stdout[-3000:] + first.stderr[-3000:]
    counts = markup_counts(first.stdout)
    assert counts[0] == FUNCTIONS and counts[1] == LABELS and counts[4] == 0
    second = run_setup(ghidra_home, copy, folder)
    assert second.returncode == 0, second.stdout[-3000:]
    assert markup_counts(second.stdout) == counts
