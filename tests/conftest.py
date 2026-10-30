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
