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


def test_lock_message_detected():
    import ghidra_run
    assert ghidra_run.is_locked('ERROR Unable to lock project! /x/renogy_bms (HeadlessAnalyzer)')
    assert not ghidra_run.is_locked('INFO  REPORT: Import succeeded')


@pytest.mark.ghidra
def test_project_held_open_is_refused(ghidra_home, stock, tmp_path):
    import time
    first = run_setup(ghidra_home, stock, tmp_path)
    assert first.returncode == 0, first.stdout[-2000:]
    import ghidra_run
    holder = subprocess.Popen([str(ghidra_run.find_headless(ghidra_home)), str(tmp_path), 'renogy_bms',
                               '-process', 'stock_global.s19', '-noanalysis', '-readOnly',
                               '-scriptPath', str(ROOT / 'tests' / 'ghidra'), '-postScript', 'HoldProject.java', '30'],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        for line in holder.stdout:
            if 'HOLDING' in line:
                break
        result = run_setup(ghidra_home, stock, tmp_path)
    finally:
        holder.kill()
        holder.wait()
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
