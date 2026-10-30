import json
import subprocess
import sys

import pytest

from conftest import ROOT
import audit_image
import build_recipe
import s19

TOOL = ROOT / 'tools' / 'audit_image.py'
NAMES = [r['name'] for r in json.loads((ROOT / 'recipes' / 'recipes.json').read_text())]


@pytest.fixture(scope='module')
def built(tmp_path_factory):
    import os
    from pathlib import Path
    if not os.environ.get('RENOGY_STOCK_S19'):
        pytest.skip('set RENOGY_STOCK_S19 to your stock .s19')
    stock = Path(os.environ['RENOGY_STOCK_S19'])
    d = tmp_path_factory.mktemp('recipes')
    out = {}
    for name in NAMES:
        out[name] = d / f'{name}.s19'
        build_recipe.build(stock, name, out[name])
    return out


@pytest.mark.parametrize('name', NAMES)
def test_recipe_fully_reviewed(stock, built, name):
    report = audit_image.audit(stock, built[name])
    assert report.recipe == name
    assert report.size_ok
    assert report.changed
    assert all(label != 'unreviewed' for _, _, label in report.changed)


def test_stock_has_no_changes(stock):
    report = audit_image.audit(stock, stock)
    assert report.changed == []
    assert report.recipe is None


def write_with_byte_changed(src, dst, global_address, value):
    lines = []
    for line in src.read_text().splitlines():
        r = s19.parse_line(line)
        if r and r.kind in (1, 2):
            start = audit_image.global_address(r.kind, r.address)
            if start <= global_address < start + len(r.data):
                data = bytearray(r.data)
                data[global_address - start] = value
                line = s19.format_record(r.kind, r.address, bytes(data))
        lines.append(line)
    dst.write_text('\r\n'.join(lines) + '\r\n')


def test_extra_change_is_unreviewed(stock, built, tmp_path):
    # 7F8100 lies in page FE code that no recipe touches.
    own = tmp_path / 'own.s19'
    stock_value = audit_image.memory(stock)[0x7F8100]
    write_with_byte_changed(built['uvcharge'], own, 0x7F8100, stock_value ^ 0xFF)
    report = audit_image.audit(stock, own)
    assert report.recipe is None
    assert [(a, b) for a, b, label in report.changed if label == 'unreviewed'] == [(0x7F8100, 0x7F8101)]


def test_over_size_cap(stock, tmp_path):
    big = tmp_path / 'big.s19'
    lines = stock.read_text().splitlines()
    extra = audit_image.MAX_IMAGE_BYTES - audit_image.programmed_bytes(stock) + 1
    page, local = 0xE1, 0x8000
    while extra > 0:
        n = min(32, extra)
        lines.insert(-1, s19.format_record(2, page << 16 | local, b'\xA7' * n))
        local += n
        if local >= 0xC000:
            page, local = page + 1, 0x8000
        extra -= n
    big.write_text('\r\n'.join(lines) + '\r\n')
    report = audit_image.audit(stock, big)
    assert report.size == audit_image.MAX_IMAGE_BYTES + 1 and not report.size_ok
    result = subprocess.run([sys.executable, str(TOOL), str(stock), str(big)], capture_output=True, text=True)
    assert result.returncode == 1


def test_cli_reports_recipe(stock, built):
    result = subprocess.run([sys.executable, str(TOOL), str(stock), str(built['cvl'])], capture_output=True, text=True)
    assert result.returncode == 0, result.stdout
    assert 'recipe cvl' in result.stdout and '0 unreviewed bytes' in result.stdout
    assert 'Patched' not in result.stdout
