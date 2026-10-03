"""Real Linux child-process tests for wall-time, memory, output and cleanup.

These are skipped on non-POSIX hosts: Windows library tests cannot prove Linux
OS-level limits, so they are not used as a substitute. The memory test goes
through the same bootstrap (`apply_process_memory_limit`) the production child
uses; the parent does not set limits via `preexec_fn`.
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


def _run_script(script, limits, data=b"", script_args=None):
    header = {"sizeBytes": len(data)}
    return _ScriptSupervisor(script, script_args).run(header, data, limits)


def _pdf_request(data):
    return ParseRequest(
        document_id="doc",
        media_type=PDF_MEDIA_TYPE,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def test_wall_timeout_kills_and_reaps(tmp_path):
    pid_file = tmp_path / "child.pid"
    script = (
        "import os, sys, time\n"
        "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
        "time.sleep(30)\n"
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, ParseLimits(wall_seconds=1.0), script_args=[str(pid_file)])
    assert excinfo.value.code == errors.TIMEOUT

    pid = int(pid_file.read_text())
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


def test_wall_deadline_includes_input_stall():
    # The child never reads stdin, so the parent's writer blocks on a payload
    # larger than the pipe buffer. The deadline must still fire.
    big = b"\x00" * (2 * 1024 * 1024)
    script = "import time\ntime.sleep(30)\n"
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, ParseLimits(wall_seconds=1.0), data=big)
    assert excinfo.value.code == errors.TIMEOUT


def test_memory_limit_is_enforced_via_child_bootstrap():
    script = (
        "import sys\n"
        "from ai_worker.infrastructure.child import apply_process_memory_limit\n"
        "apply_process_memory_limit(512 * 1024 * 1024)\n"
        "try:\n"
        "    buf = bytearray(900 * 1024 * 1024)\n"
        "    print('{\"ok\": true, \"result\": {\"allocated\": %d}}' % len(buf))\n"
        "except MemoryError:\n"
        "    print('{\"ok\": false, \"error\": {\"code\": \"MEMORY_LIMIT_EXCEEDED\"}}')\n"
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, ParseLimits(wall_seconds=15.0))
    assert excinfo.value.code == errors.MEMORY_LIMIT_EXCEEDED


def test_output_limit_races_child_exit():
    limits = ParseLimits(max_result_json_bytes=1024, wall_seconds=10.0)
    script = (
        "import sys\n"
        "sys.stdout.write('x' * (300 * 1024))\n"
        "sys.stdout.flush()\n"
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, limits)
    assert excinfo.value.code == errors.OUTPUT_LIMIT_EXCEEDED


def test_nonzero_exit_with_success_json_is_failure():
    script = (
        "import sys\n"
        "print('{\"ok\": true, \"result\": {}}')\n"
        "sys.exit(3)\n"
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, ParseLimits(wall_seconds=10.0))
    assert excinfo.value.code == errors.PARSER_CRASHED


def test_invalid_envelope_list_is_internal_error():
    script = "print('[1, 2, 3]')\n"
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, ParseLimits(wall_seconds=10.0))
    assert excinfo.value.code == errors.INTERNAL_ERROR


def test_unknown_error_code_is_internal_error():
    script = "print('{\"ok\": false, \"error\": {\"code\": \"TOTALLY_BOGUS\"}}')\n"
    with pytest.raises(errors.ParseFailure) as excinfo:
        _run_script(script, ParseLimits(wall_seconds=10.0))
    assert excinfo.value.code == errors.INTERNAL_ERROR


def test_isolated_parse_succeeds_after_failures():
    data = build_pdf(["정상", None, "문서"])
    result = composition.parse_isolated(_pdf_request(data), data, DEFAULT_LIMITS)
    assert result["kind"] == "pdf"
    assert result["pdf"]["pages"][0]["text"] == "정상"
