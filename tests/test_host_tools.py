import subprocess
import sys

import pytest

from conftest import ROOT
import rvc_command
import scrub_check
import ws500_serial

TOOLS = ROOT / 'tools'


def test_heater_frame():
    identifier, data = rvc_command.frame('heater', 300, source=0xF9, dest=0x70)
    assert identifier == 0x18EF70F9
    assert data == bytes.fromhex('AA55 0000 0000 012C')


def test_mask_frame():
    identifier, data = rvc_command.frame('mask', 600, source=0xF9, dest=0x70)
    assert identifier == 0x18EF70F9
    assert data[:2] == bytes.fromhex('4D53') and data[-2:] == bytes.fromhex('0258')


def test_lease_out_of_range():
    with pytest.raises(ValueError):
        rvc_command.frame('heater', 0x10000, source=0xF9, dest=0x70)


def test_status_decode():
    # relay requested and on, session armed, mask active, state 3, lease 300.0 s, min temp -12.5 C
    status = rvc_command.parse_status(bytes.fromhex('70 05 0B 00 B8 0B 83 FF'))
    assert status['relay_request'] and status['relay_output'] and not status['relay_ready']
    assert status['session_armed'] and status['mask_active'] and not status['overtemp_inhibit']
    assert status['state3']
    assert status['lease_s'] == 300.0
    assert status['min_temp_c'] == -12.5
    assert status['lowtemp_alarm'] == 0


def test_status_rejects_wrong_tag():
    with pytest.raises(ValueError):
        rvc_command.parse_status(bytes(8))


def test_dry_run_needs_no_can_library():
    code = ('import sys, runpy; sys.modules["can"] = None; sys.argv = ["rvc_command.py", "--dry-run", "heater", "30"]; '
            f'runpy.run_path({str(TOOLS / "rvc_command.py")!r}, run_name="__main__")')
    result = subprocess.run([sys.executable, '-c', code], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == '18EF70F9#AA5500000000012C'


def test_no_legacy_option():
    result = subprocess.run([sys.executable, str(TOOLS / 'rvc_command.py'), '--help'], capture_output=True, text=True)
    assert '--legacy' not in result.stdout and '999' not in result.stdout


@pytest.mark.parametrize('cmd', ['$SCV:...', '$cp:1', '$RBT:'])
def test_ws500_write_refused_before_opening_port(cmd, tmp_path):
    missing = tmp_path / 'no-such-port'
    result = subprocess.run([sys.executable, str(TOOLS / 'ws500_serial.py'), '--port', str(missing), cmd],
                            capture_output=True, text=True)
    assert result.returncode != 0
    assert 'allow-write' in result.stdout + result.stderr
    assert 'could not open' not in (result.stdout + result.stderr).lower()


def test_ws500_read_commands_allowed():
    assert not ws500_serial.looks_like_write('$RAS:')
    assert ws500_serial.looks_like_write('$SCV:52.0')


def test_ws500_port_required_off_linux(monkeypatch):
    monkeypatch.setattr(ws500_serial.sys, 'platform', 'win32')
    assert ws500_serial.default_port() is None


def test_host_tools_scrubbed():
    paths = {f.path for f in scrub_check.scan(ROOT, [])}
    assert not paths & {'tools/rvc_command.py', 'tools/ws500_serial.py'}
