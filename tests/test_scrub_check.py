import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import scrub_check  # noqa: E402


@pytest.fixture
def repo(tmp_path):
    subprocess.run(['git', 'init', '-q', str(tmp_path)], check=True)
    return tmp_path


def write(repo, name, content):
    path = repo / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        path.write_bytes(content)
    else:
        path.write_text(content)
    return path


def reasons(repo, blocklist=()):
    return [f.reason for f in scrub_check.scan(repo, list(blocklist))]


def test_clean_tree_has_no_findings(repo):
    write(repo, 'README.md', 'Nothing to see here.\n')
    assert scrub_check.scan(repo, []) == []


# Sample strings are built from pieces so this file passes the check itself.
H, U, AT = '/' + 'home', 'Users', '@'


@pytest.mark.parametrize('text', ['see ' + H + '/someone/x', 'see C:\\' + U + '\\someone\\x', 'see /' + U + '/someone/x'])
def test_home_path(repo, text):
    write(repo, 'a.md', text + '\n')
    assert reasons(repo) == ['home path']


def test_foreign_email(repo):
    write(repo, 'a.md', 'mail someone' + AT + 'gmail.com\n')
    assert reasons(repo) == ['email']


def test_allowed_emails(repo):
    write(repo, 'a.md', 'dbergl@hotmail.com and noreply@anthropic.com\n')
    assert reasons(repo) == []


def test_s_record_line(repo):
    write(repo, 'a.txt', 'S1130000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEC\n')
    assert reasons(repo) == ['s-record']


def test_binary_file(repo):
    write(repo, 'a.bin.txt', b'abc\x00def')
    assert reasons(repo) == ['binary']


@pytest.mark.parametrize('name', ['x.s19', 'x.gpr', 'x.dfu', 'decompiled.c'])
def test_forbidden_file(repo, name):
    write(repo, name, 'harmless\n')
    assert reasons(repo) == ['forbidden file']


def test_blocklist_case_insensitive(repo):
    write(repo, 'a.md', 'parked next to SecretCoach today\n')
    assert reasons(repo, ['secretcoach']) == ['blocklist']


def test_blocklist_file_itself_not_scanned(repo):
    write(repo, '.scrub-blocklist', 'secretcoach\nsomeone' + AT + 'gmail.com\n')
    assert reasons(repo, ['secretcoach']) == []


def test_finding_has_path_and_line(repo):
    write(repo, 'docs/a.md', 'ok\nok\n' + H + '/x/y\n')
    [finding] = scrub_check.scan(repo, [])
    assert (finding.path, finding.line) == ('docs/a.md', 3)


def test_cli_exit_codes(repo):
    tool = Path(scrub_check.__file__)
    write(repo, 'a.md', 'fine\n')
    assert subprocess.run([sys.executable, str(tool), '--root', str(repo)], capture_output=True).returncode == 0
    write(repo, 'b.md', H + '/x/y\n')
    result = subprocess.run([sys.executable, str(tool), '--root', str(repo)], capture_output=True, text=True)
    assert result.returncode == 1
    assert 'b.md:1: home path' in result.stdout


def test_kit_itself_is_clean():
    root = Path(__file__).resolve().parents[1]
    blocklist = scrub_check.read_blocklist(root / scrub_check.BLOCKLIST_NAME)
    assert scrub_check.scan(root, blocklist) == []
