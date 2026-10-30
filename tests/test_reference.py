import re
import subprocess
import sys

import pytest

from conftest import ROOT
import gen_reference
import markup

ARTIFACTS = re.compile(r'FUN_|DAT_|LAB_|undefined|\{|;\s*(\\n|$)')


def test_every_function_has_a_summary():
    missing = [r['name'] for r in markup.read('functions.tsv') if not r['summary'].strip()]
    assert not missing


def test_every_label_has_a_summary():
    missing = [r['name'] for r in markup.read('labels.tsv') if not r['summary'].strip()]
    assert not missing


@pytest.mark.parametrize('name,columns', [('functions.tsv', ('summary', 'details')), ('labels.tsv', ('summary',))])
def test_no_decompiler_artifacts(name, columns):
    bad = [(r['name'], c) for r in markup.read(name) for c in columns if ARTIFACTS.search(r[c])]
    assert not bad


def test_summaries_are_one_line():
    for r in markup.read('functions.tsv') + markup.read('labels.tsv'):
        assert '\\n' not in r['summary'] and len(r['summary']) <= 200, r['name']


def test_reference_is_current():
    assert (ROOT / 'docs' / 'function-reference.md').read_text() == gen_reference.render(markup.MARKUP)
    assert subprocess.run([sys.executable, str(ROOT / 'tools' / 'gen_reference.py'), '--check']).returncode == 0


def test_render_groups_by_area():
    text = gen_reference.render(markup.MARKUP)
    assert '## alarm' in text and '## heater' in text and '## Variables and registers' in text
    assert text.index('## other') > text.index('## alarm')
