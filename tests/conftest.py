import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))


@pytest.fixture
def stock():
    path = os.environ.get('RENOGY_STOCK_S19')
    if not path:
        pytest.skip('set RENOGY_STOCK_S19 to your stock .s19')
    return Path(path)


def pytest_configure(config):
    config.addinivalue_line('markers', 'ghidra: needs GHIDRA_HOME (a Ghidra 12.1.4 install); takes minutes')


@pytest.fixture
def ghidra_home():
    path = os.environ.get('GHIDRA_HOME')
    if not path:
        pytest.skip('set GHIDRA_HOME to your Ghidra 12.1.4 folder')
    return Path(path)
