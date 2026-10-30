import hashlib
import shutil
import subprocess
import sys

import pytest

from conftest import ROOT
import to_global
import verify_stock

STOCK_GLOBAL_SHA256 = 'f15d66f459d9bc59306d66b5390f34e81e8b23d96d4ad5aaca1c20750bfee8c6'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_stock_accepted(stock):
    ok, reason = verify_stock.check(stock)
    assert ok, reason


def test_one_changed_digit_rejected(stock, tmp_path):
    data = bytearray(stock.read_bytes())
    i = data.index(b'S1') + 10
    data[i] = ord('0') if data[i] != ord('0') else ord('1')
    bad = tmp_path / 'bad.s19'
    bad.write_bytes(bytes(data))
    ok, reason = verify_stock.check(bad)
    assert not ok and 'not the stock V137.4.5 image' in reason


def test_lf_line_endings_explained(stock, tmp_path):
    lf = tmp_path / 'lf.s19'
    lf.write_bytes(stock.read_bytes().replace(b'\r\n', b'\n'))
    ok, reason = verify_stock.check(lf)
    assert not ok and 'line endings' in reason


def test_missing_file(tmp_path):
    ok, reason = verify_stock.check(tmp_path / 'nope.s19')
    assert not ok and 'not found' in reason


def test_convert_matches_reference(stock, tmp_path):
    out = tmp_path / 'g.s19'
    assert to_global.convert(stock, out) > 0
    assert sha256(out) == STOCK_GLOBAL_SHA256


def test_convert_refuses_overwriting_input(stock, tmp_path):
    copy = tmp_path / 'stock.s19'
    shutil.copy(stock, copy)
    with pytest.raises(ValueError):
        to_global.convert(copy, copy)
    assert verify_stock.check(copy)[0]


def test_clis_with_spaces_and_brackets(stock, tmp_path):
    folder = tmp_path / 'RBT210 Update Instructions 137.4.5'
    folder.mkdir()
    copy = folder / 'V137.4.5[20240719a].s19'
    shutil.copy(stock, copy)
    tools = ROOT / 'tools'
    assert subprocess.run([sys.executable, str(tools / 'verify_stock.py'), str(copy)]).returncode == 0
    out = folder / 'stock global [x].s19'
    assert subprocess.run([sys.executable, str(tools / 'to_global.py'), str(copy), str(out)]).returncode == 0
    assert sha256(out) == STOCK_GLOBAL_SHA256
