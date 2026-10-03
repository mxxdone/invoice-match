"""Parent side of the isolated parser process.

The parent bounds the child on four axes:

* **input** - the declared size is verified, the document is streamed once;
* **wall time** - the child is killed and reaped after ``wall_seconds``;
* **output** - stdout is read incrementally and the child is killed as soon as
  it exceeds ``max_result_json_bytes`` (no unbounded ``communicate``);
* **memory** - on Linux the child's address space is limited with
  ``RLIMIT_AS`` before it starts.

If the host cannot enforce the OS memory limit, the production entry fails
closed with ``UNSUPPORTED_HOST`` instead of pretending the limit holds.
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import threading
import time

from ai_worker.application.limits import DEFAULT_LIMITS, ParseLimits
from ai_worker.domain import errors
from ai_worker.infrastructure import protocol

_IS_POSIX = os.name == "posix"
_READ_CHUNK = 64 * 1024
_OUTPUT_SLACK = 256 * 1024
_JOIN_TIMEOUT = 5.0

_CHILD_MODULE = "ai_worker.infrastructure.child"


def _apply_child_memory_limit(memory_bytes: int) -> None:
    import resource

    resource.setrlimit(resource.RLIMIT_AS, (memory_bytes, memory_bytes))


def _child_env() -> dict[str, str]:
    package_root = os.path.dirname(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    )
    existing = os.environ.get("PYTHONPATH")
    python_path = package_root + (os.pathsep + existing if existing else "")
    env = {
        "PYTHONPATH": python_path,
        "PATH": os.environ.get("PATH", ""),
        "PYTHONIOENCODING": "utf-8",
        "PYTHONDONTWRITEBYTECODE": "1",
    }
    for key in ("LANG", "LC_ALL", "SYSTEMROOT", "TEMP", "TMP"):
        if key in os.environ:
            env[key] = os.environ[key]
    return env


class ProcessSupervisor:
    def __init__(self, child_module: str = _CHILD_MODULE) -> None:
        self._child_module = child_module

    def _build_command(self) -> list[str]:
        return [sys.executable, "-m", self._child_module]

    def run(
        self, header: dict, data: bytes, limits: ParseLimits = DEFAULT_LIMITS
    ) -> dict:
        if not _IS_POSIX:
            raise errors.ParseFailure(errors.UNSUPPORTED_HOST)
        if header.get("sizeBytes") != len(data):
            raise errors.ParseFailure(errors.SIZE_MISMATCH)
        if len(data) > limits.max_input_bytes:
            raise errors.ParseFailure(errors.INPUT_TOO_LARGE)

        payload = protocol.encode_request(header, data)
        command = self._build_command()

        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
            preexec_fn=lambda: _apply_child_memory_limit(limits.memory_bytes),
            env=_child_env(),
        )

        self._pump_stdin(process, payload)
        reader = _OutputReader(process.stdout, limits.max_result_json_bytes + _OUTPUT_SLACK)
        reader.start()
        stderr_drain = _Drain(process.stderr, 64 * 1024)
        stderr_drain.start()

        deadline = time.monotonic() + limits.wall_seconds
        while True:
            if reader.exceeded:
                self._terminate(process)
                reader.join(_JOIN_TIMEOUT)
                stderr_drain.join(_JOIN_TIMEOUT)
                raise errors.ParseFailure(errors.OUTPUT_LIMIT_EXCEEDED)
            if process.poll() is not None:
                break
            if time.monotonic() >= deadline:
                self._terminate(process)
                reader.join(_JOIN_TIMEOUT)
                stderr_drain.join(_JOIN_TIMEOUT)
                raise errors.ParseFailure(errors.TIMEOUT)
            time.sleep(0.02)

        reader.join(_JOIN_TIMEOUT)
        stderr_drain.join(_JOIN_TIMEOUT)

        raw_output = reader.data
        if not raw_output:
            if process.returncode != 0:
                raise errors.ParseFailure(errors.PARSER_CRASHED)
            raise errors.ParseFailure(errors.INTERNAL_ERROR)

        try:
            envelope = json.loads(raw_output.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            if process.returncode != 0:
                raise errors.ParseFailure(errors.PARSER_CRASHED) from exc
            raise errors.ParseFailure(errors.INTERNAL_ERROR) from exc

        if envelope.get("ok") is True and isinstance(envelope.get("result"), dict):
            return envelope["result"]
        code = (envelope.get("error") or {}).get("code")
        raise errors.ParseFailure(code or errors.INTERNAL_ERROR)

    @staticmethod
    def _pump_stdin(process: subprocess.Popen, payload: bytes) -> None:
        def write() -> None:
            try:
                process.stdin.write(payload)
                process.stdin.flush()
            except (BrokenPipeError, OSError):
                pass
            finally:
                try:
                    process.stdin.close()
                except (BrokenPipeError, OSError):
                    pass

        thread = threading.Thread(target=write, name="parser-stdin", daemon=True)
        thread.start()
        thread.join(_JOIN_TIMEOUT)

    @staticmethod
    def _terminate(process: subprocess.Popen) -> None:
        try:
            if _IS_POSIX:
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            else:
                process.kill()
        except (ProcessLookupError, PermissionError, OSError):
            pass
        try:
            process.wait(timeout=_JOIN_TIMEOUT)
        except subprocess.TimeoutExpired:
            pass
        for stream in (process.stdin, process.stdout, process.stderr):
            try:
                if stream is not None:
                    stream.close()
            except OSError:
                pass


class _OutputReader(threading.Thread):
    def __init__(self, stream, cap: int) -> None:
        super().__init__(name="parser-stdout", daemon=True)
        self._stream = stream
        self._cap = cap
        self.data = b""
        self.exceeded = False

    def run(self) -> None:
        chunks: list[bytes] = []
        total = 0
        try:
            while True:
                chunk = self._stream.read(_READ_CHUNK)
                if not chunk:
                    break
                total += len(chunk)
                if total > self._cap:
                    self.exceeded = True
                    break
                chunks.append(chunk)
        except (OSError, ValueError):
            pass
        finally:
            if not self.exceeded:
                self.data = b"".join(chunks)
            try:
                self._stream.close()
            except OSError:
                pass


class _Drain(threading.Thread):
    """Consumes stderr so the child never blocks, discarding its content."""

    def __init__(self, stream, cap: int) -> None:
        super().__init__(name="parser-stderr", daemon=True)
        self._stream = stream
        self._cap = cap

    def run(self) -> None:
        total = 0
        try:
            while True:
                chunk = self._stream.read(_READ_CHUNK)
                if not chunk:
                    break
                total += len(chunk)
                if total > self._cap:
                    break
        except (OSError, ValueError):
            pass
        finally:
            try:
                self._stream.close()
            except OSError:
                pass
