"""Read the Ghidra markup files in markup/ (tab-separated, # comments, header row)."""
import csv
from pathlib import Path

MARKUP = Path(__file__).resolve().parents[1] / 'markup'
COLUMNS = {
    'functions.tsv': ['address', 'name', 'summary', 'details'],
    'labels.tsv': ['address', 'name', 'kind', 'summary'],
    'structs.tsv': ['struct', 'offset', 'type', 'field', 'comment'],
    'struct_uses.tsv': ['address', 'struct', 'count'],
}


def read(name: str, directory: Path = MARKUP) -> list[dict[str, str]]:
    lines = [l for l in (Path(directory) / name).read_text().splitlines() if l and not l.startswith('#')]
    rows = list(csv.DictReader(lines, delimiter='\t', quoting=csv.QUOTE_NONE))
    if not lines or lines[0].split('\t') != COLUMNS[name]:
        raise ValueError(f'{name}: header must be {COLUMNS[name]}')
    return rows
