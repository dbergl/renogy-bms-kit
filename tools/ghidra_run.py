"""Run Ghidra's headless analyzer (support/analyzeHeadless) for the kit's tools."""
import os
import re
import subprocess
from pathlib import Path

EXPECTED_VERSION = '12.1.4'
KIT_GHIDRA_SCRIPTS = Path(__file__).resolve().parents[1] / 'ghidra'


class GhidraNotFound(Exception):
    pass


class ProjectLocked(Exception):
    pass


def find_headless(ghidra_home: Path) -> Path:
    name = 'analyzeHeadless.bat' if os.name == 'nt' else 'analyzeHeadless'
    path = Path(ghidra_home) / 'support' / name
    if not path.is_file():
        raise GhidraNotFound(f'no {path}: --ghidra must be the Ghidra install folder, the one that '
                             f'contains support/analyzeHeadless')
    return path


def ghidra_version(ghidra_home: Path) -> str:
    props = Path(ghidra_home) / 'Ghidra' / 'application.properties'
    m = re.search(r'^application\.version=(.+)$', props.read_text(), re.M) if props.is_file() else None
    return m.group(1).strip() if m else 'unknown'


def version_warning(ghidra_home: Path) -> str | None:
    version = ghidra_version(ghidra_home)
    if version != EXPECTED_VERSION:
        return f'warning: Ghidra {version}; the kit was tested with {EXPECTED_VERSION}'
    return None


def run(ghidra_home: Path, project_dir: Path, project_name: str, args: list[str],
        log: Path | None = None) -> subprocess.CompletedProcess:
    """analyzeHeadless <project_dir> <project_name> <args...>, arguments passed as a list (no shell)."""
    headless = find_headless(ghidra_home)
    project_dir = Path(project_dir)
    lock = project_dir / f'{project_name}.lock'
    if lock.exists():
        raise ProjectLocked(f'{lock} exists: Ghidra is open on this project, or closed without cleaning up. '
                            'Close Ghidra; if it is not running, delete the .lock file.')
    project_dir.mkdir(parents=True, exist_ok=True)
    result = subprocess.run([str(headless), str(project_dir), project_name, *args],
                            capture_output=True, text=True, errors='replace')
    if log:
        Path(log).write_text(result.stdout + result.stderr)
    return result
