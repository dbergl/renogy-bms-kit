import json
import re
from pathlib import Path

import pytest

from conftest import ROOT
import markup
import scrub_check

TOPIC_DOCS = ['firmware-map.md', 'can-protocol.md', 'alarms-and-contactors.md', 'heater.md', 'cell-balancing.md',
              'charge-limits.md', 'aps500-ws500.md', 'emulator-testing.md']
GUIDES = ['ghidra-walkthrough.md', 'flashing-and-recovery.md', 'function-reference.md']
RECIPES = json.loads((ROOT / 'recipes' / 'recipes.json').read_text())
DOCS = sorted((ROOT / 'docs').glob('*.md')) + sorted((ROOT / 'recipes').glob('*.md')) + [ROOT / 'README.md']

LINK = re.compile(r'\[[^\]]*\]\(([^)\s]+)\)')
IDENT = re.compile(r'`([a-z][a-z0-9]*_[a-z0-9_]+)`')
# Backticked snake_case words that are not firmware names: tool, harness and setting names.
ALLOWED = {
    'build_recipe', 'make_patch', 'audit_image', 'verify_stock', 'to_global', 'ghidra_setup', 'run_emulator_tests',
    'rvc_command', 'ws500_serial', 'scrub_check', 'gen_reference', 'image_flags', 'stock_global', 'rvc_status',
    'cvl_get', 'cvl_cmp', 'cvl_stock', 'cvl_ret', 'mask_toggle', 'mask_copy', 'masked_copy', 'mask_tick', 'mask_publish',
    'heater_publish', 'ack_stub', 'uv_mask', 'uv_cap', 'uv_copy', 'uv_mode', 'slew_store', 'bms_settings', 'alarm_object',
    'struct_uses', 'tests_json', 'renogy_bms',
}


def setting_fields():
    return {r['field'] for r in markup.read('structs.tsv')}


def firmware_names():
    return {r['name'] for r in markup.read('functions.tsv')} | {r['name'] for r in markup.read('labels.tsv')}


@pytest.mark.parametrize('name', TOPIC_DOCS + GUIDES)
def test_doc_exists(name):
    assert (ROOT / 'docs' / name).is_file()


@pytest.mark.parametrize('doc', DOCS, ids=lambda p: str(p.relative_to(ROOT)))
def test_relative_links_resolve(doc):
    for target in LINK.findall(doc.read_text()):
        if re.match(r'[a-z]+:', target) or target.startswith('#'):
            continue
        path = (doc.parent / target.split('#', 1)[0]).resolve()
        assert path.exists(), f'{doc.name}: broken link {target}'


@pytest.mark.parametrize('doc', DOCS, ids=lambda p: str(p.relative_to(ROOT)))
def test_identifiers_are_known(doc):
    known = firmware_names() | setting_fields() | ALLOWED
    unknown = sorted({i for i in IDENT.findall(doc.read_text()) if i not in known})
    assert not unknown, f'{doc.name}: not in markup or the allowlist: {unknown}'


@pytest.mark.parametrize('doc', [d for d in DOCS if d.parent.name == 'docs'], ids=lambda p: p.name)
def test_no_dates_in_docs(doc):
    assert not re.search(r'20\d\d-\d\d-\d\d', doc.read_text()), f'{doc.name} has a date'


@pytest.mark.parametrize('recipe', RECIPES, ids=[r['name'] for r in RECIPES])
def test_recipe_doc(recipe):
    text = (ROOT / 'recipes' / f'{recipe["name"]}.md').read_text()
    assert recipe['sha256'] in text
    assert f'build_recipe.py <stock.s19> {recipe["name"]}' in text


def test_readme_links_every_doc_and_recipe():
    text = (ROOT / 'README.md').read_text()
    for name in TOPIC_DOCS + GUIDES:
        assert f'docs/{name}' in text, name
    for r in RECIPES:
        assert f'recipes/{r["name"]}.md' in text, r['name']


def test_docs_scrubbed():
    root_findings = [f for f in scrub_check.scan(ROOT, scrub_check.read_blocklist(ROOT / scrub_check.BLOCKLIST_NAME))
                     if f.path.endswith('.md')]
    assert root_findings == []
