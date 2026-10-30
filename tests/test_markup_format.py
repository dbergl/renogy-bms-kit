import re

import pytest

import markup

ADDRESS = re.compile(r'^[0-9a-f]{6}$')
TYPES = re.compile(r'^(byte|sbyte|word|sword|dword|(byte|char)\[\d+\])$')


@pytest.mark.parametrize('name', sorted(markup.COLUMNS))
def test_parses_with_header(name):
    rows = markup.read(name)
    assert rows
    for row in rows:
        assert None not in row.values(), (name, row)   # no row has fewer columns


@pytest.mark.parametrize('name', ['functions.tsv', 'labels.tsv', 'struct_uses.tsv'])
def test_addresses_unique_and_well_formed(name):
    addresses = [r['address'] for r in markup.read(name)]
    assert all(ADDRESS.match(a) for a in addresses), [a for a in addresses if not ADDRESS.match(a)]
    assert len(addresses) == len(set(addresses))


@pytest.mark.parametrize('name', ['functions.tsv', 'labels.tsv'])
def test_names_unique(name):
    names = [r['name'] for r in markup.read(name)]
    assert len(names) == len(set(names))


def test_label_kinds():
    for r in markup.read('labels.tsv'):
        a = int(r['address'], 16)
        expected = 'io' if a < 0x800 else 'ram' if 0x0FE000 <= a < 0x100000 else 'flash'
        assert r['kind'] == expected, r


def test_struct_fields():
    structs = {}
    for r in markup.read('structs.tsv'):
        structs.setdefault(r['struct'], []).append(r)
    names = set(structs)
    for fields in structs.values():
        for f in fields:
            assert TYPES.match(f['type']) or f['type'] in names, f
        offsets = [int(f['offset']) for f in fields]
        assert offsets == sorted(offsets) and len(offsets) == len(set(offsets))


def test_struct_uses_defined():
    defined = {r['struct'] for r in markup.read('structs.tsv')}
    for r in markup.read('struct_uses.tsv'):
        assert r['struct'] in defined and int(r['count']) >= 1


def test_settings_struct_covers_all_eleven_frames():
    fields = [r for r in markup.read('structs.tsv') if r['struct'] == 'bms_settings']
    assert {int(f['offset']) // 8 for f in fields} == set(range(11))
    ir3 = next(f for f in fields if f['field'] == 'IR3')
    assert int(ir3['offset']) == 0x2674 - 0x2626 and ir3['type'] == 'word'
