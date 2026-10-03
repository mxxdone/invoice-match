"""Real Linux child-process tests for wall-time, memory, output and cleanup.

These are skipped on non-POSIX hosts: Windows library tests cannot prove Linux
OS-level limits, so they are not used as a substitute.
"""

import hashlib
import os
import sys

import pytest
from fixtures import PDF_MEDIA_TYPE, build_pdf

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS, ParseLimits
from ai_worker.application.service import ParseRequest
from ai_worker.domain import errors
from ai_worker.infrastructure.process_supervisor import ProcessSupervisor

pytestmark = pytest.mark.skipif(
    os.name != "posix", reason="requires Linux OS resource limits"
)


class _ScriptSupervisor(ProcessSupervisor):
    def __init__(self, script: str, script_args: list[str] | None = None) -> None:
        super().__init__(child_module="ai_worker.infrastructure.child")
        self._script = script
        self._script_args = script_args or []

    def _build_command(self) -> list[str]:
        return [sys.executable, "-c", self._script, *self._script_args]


def _run_script(script, limits, script_args=None):
    header = {"sizeBytes": 0}
    return _ScriptSupervisor(script, script_args).run(header, b"", limits)


def _pdf_request(data):
    return ParseRequest(
        document_id="doc",
        media_type=PDF_MEDIA_TYPE,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def test_wall_timeout_is_killed_reaped_and_recovers(tmp_path):
    pid_file = tmp_path / "child.pid"
    script = (
        "import os, sys, time\n"
        "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
        "time.sleep(30)\n"
    )
    limits = ParseLimits(wall_seconds=1.0)
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, limits, [str(pid_file)])
    assert excinfo.value.code == errors.TIMEOUT

    pid = int(pid_file.read_text())
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)

    data = build_pdf(["회복"])
    composition.parse_isolated(_pdf_request(data), data, DEFAULT_LIMITS)


def test_memory_limit_is_enforced():
    script = (
        "import sys\n"
        "try:\n"
        "    buf = bytearray(900 * 1024 * 1024)\n"
        "    print('{\"ok\": true, \"result\": {\"allocated\": %d}}' % len(buf))\n"
        "except MemoryError:\n"
        "    print('{\"ok\": false, \"error\": {\"code\": \"MEMORY_LIMIT_EXCEEDED\"}}')\n"
    )
    limits = ParseLimits(wall_seconds=15.0)
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, limits)
    assert excinfo.value.code == errors.MEMORY_LIMIT_EXCEEDED


def test_output_limit_kills_child():
    script = "import sys\nsys.stdout.write('x' * (8 * 1024 * 1024))\nsys.stdout.flush()\n"
    limits = ParseLimits(wall_seconds=10.0)
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, limits)
    assert excinfo.value.code == errors.OUTPUT_LIMIT_EXCEEDED


def test_isolated_parse_succeeds_after_failures():
    data = build_pdf(["정상", None, "문서"])
    result = composition.parse_isolated(_pdf_request(data), data, DEFAULT_LIMITS)
    assert result["kind"] == "pdf"
    assert result["pdf"]["pages"][0]["text"] == "정상"
