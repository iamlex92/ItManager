"""WinRM commands stop at the deadline instead of holding the request for as long as a hung script runs."""

import base64

from winrm.exceptions import WinRMOperationTimeoutError

from app.runbooks import real
from app.runbooks.real import Login

from .conftest import make_settings


class FakeProtocol:
    def __init__(self, chunks):
        self.chunks = list(chunks)
        self.cleaned = self.closed = False
        self.command = ""

    def open_shell(self):
        return "shell"

    def run_command(self, shell_id, command):
        self.command = command
        return "cmd"

    def get_command_output_raw(self, shell_id, command_id):
        item = self.chunks.pop(0) if self.chunks else WinRMOperationTimeoutError()
        if isinstance(item, Exception):
            raise item
        return item

    def cleanup_command(self, shell_id, command_id):
        self.cleaned = True

    def close_shell(self, shell_id):
        self.closed = True


class FakeSession:
    def __init__(self, protocol):
        self.protocol = protocol

    def _clean_error_msg(self, msg):
        return msg.replace(b"#< CLIXML", b"").strip()


def _shell(monkeypatch, protocol, timeout):
    import winrm

    monkeypatch.setattr(winrm, "Session", lambda *a, **k: FakeSession(protocol))
    return real.winrm_shell(make_settings(winrm_timeout_seconds=timeout), Login("D\\adm", "pw"))


def test_a_long_command_keeps_being_read_until_it_ends(monkeypatch):
    p = FakeProtocol([WinRMOperationTimeoutError(), (b"part1 ", b"", 0, False), WinRMOperationTimeoutError(),
                      (b"part2", b"#< CLIXML oops", 0, True)])  # fmt: skip
    code, out, err = _shell(monkeypatch, p, 60)("pc-01", "Write-Output 'привет'")
    assert (code, out, err) == (0, "part1 part2", "oops")
    assert p.cleaned and p.closed
    encoded = p.command.removeprefix("powershell -encodedcommand ")
    assert base64.b64decode(encoded).decode("utf-16-le") == "Write-Output 'привет'"


def test_a_hung_command_is_stopped_at_the_deadline(monkeypatch):
    p = FakeProtocol([(b"started\n", b"", 0, False)])  # then «nothing yet» forever
    times = iter([0.0, 0.0, 5.0, 11.0, 12.0])
    monkeypatch.setattr("time.monotonic", lambda: next(times))
    code, out, err = _shell(monkeypatch, p, 10)("pc-01", "Start-Sleep 3600")
    assert code == 124 and out == "started\n" and "10" in err
    assert p.cleaned and p.closed  # the command was stopped on the computer
