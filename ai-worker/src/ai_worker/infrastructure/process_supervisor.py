"""Parent side of the isolated parser process.

The parent bounds the child on four axes:

* **input** - the declared size is verified, the document is streamed once;
* **wall time** - a single deadline covers process start, input transfer and
  parsing; the child process group is killed and reaped on expiry;
* **output** - stdout is read incrementally and the child is killed as soon as
  it exceeds ``max_result_json_bytes`` (no unbounded ``communicate``);
* **memory** - the child applies ``RLIMIT_AS`` itself before importing any SDK
  (see :mod:`ai_worker.infrastructure.child`); the parent never uses
  ``preexec_fn`` in a threaded process.

Only Linux/POSIX is a supported production host; other hosts fail closed with
``UNSUPPORTED_HOST``. All owned processes, pipes and threads are reclaimed in
``finally``.
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
_STDERR_CAP = 256 * 1024
_JOIN_TIMEOUT = 5.0
_PIPE_DRAIN_TIMEOUT = 5.0
_POLL_INTERVAL = 0.02

_CHILD_MODULE = "ai_worker.infrastructure.child"


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
        max_output = limits.max_result_json_bytes + _OUTPUT_SLACK
        deadline = time.monotonic() + limits.wall_seconds

        process: subprocess.Popen | None = None
        threads: list[threading.Thread] = []
        try:
            try:
                process = subprocess.Popen(
                    self._build_command(),
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    start_new_session=True,
                    env=_child_env(),
                )
            except OSError as exc:
                raise errors.ParseFailure(errors.PARSER_CRASHED) from exc

            writer = _StdinWriter(process.stdin, payload)
            reader = _OutputReader(process.stdout, max_output)
            drain = _Drain(process.stderr, _STDERR_CAP)
            threads = [writer, reader, drain]
            for thread in threads:
                thread.start()

            failure: str | None = None
            while True:
                if reader.exceeded:
                    failure = errors.OUTPUT_LIMIT_EXCEEDED
                    break
                if process.poll() is not None:
                    break
                if time.monotonic() >= deadline:
                    failure = errors.TIMEOUT
                    break
                time.sleep(_POLL_INTERVAL)

            if failure is not None:
                self._terminate(process)
                raise errors.ParseFailure(failure)

            if not self._join(threads, _PIPE_DRAIN_TIMEOUT):
                # A descendant still holds a pipe open; kill the group and do
                # not wait indefinitely.
                self._terminate(process)
                raise errors.ParseFailure(errors.PARSER_CRASHED)

            # The child may have exited in the same instant the reader crossed
            # the cap; re-check the race before interpreting the output.
            if reader.exceeded:
                raise errors.ParseFailure(errors.OUTPUT_LIMIT_EXCEEDED)

            return self._interpret(process, reader.data, limits)
        finally:
            self._cleanup(process, threads)

    @staticmethod
    def _interpret(
        process: subprocess.Popen, raw_output: bytes, limits: ParseLimits
    ) -> dict:
        returncode = process.returncode
        envelope = None
        if raw_output:
            try:
                envelope = json.loads(raw_output.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                envelope = None

        if not isinstance(envelope, dict):
            raise errors.ParseFailure(
                errors.PARSER_CRASHED if returncode != 0 else errors.INTERNAL_ERROR
            )

        if envelope.get("ok") is True:
            # A non-zero exit is never a successful parse, even with valid JSON.
            if returncode != 0:
                raise errors.ParseFailure(errors.PARSER_CRASHED)
            result = envelope.get("result")
            if not isinstance(result, dict):
                raise errors.ParseFailure(errors.INTERNAL_ERROR)
            serialized = json.dumps(
                result, ensure_ascii=False, separators=(",", ":"), sort_keys=True
            ).encode("utf-8")
            if len(serialized) > limits.max_result_json_bytes:
                raise errors.ParseFailure(errors.RESULT_TOO_LARGE)
            return result

        error = envelope.get("error")
        code = error.get("code") if isinstance(error, dict) else None
        if errors.is_known_code(code):
            raise errors.ParseFailure(code)
        raise errors.ParseFailure(
            errors.PARSER_CRASHED if returncode != 0 else errors.INTERNAL_ERROR
        )

    @staticmethod
    def _join(threads: list[threading.Thread], timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        for thread in threads:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            thread.join(remaining)
        return all(not thread.is_alive() for thread in threads)

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
        ProcessSupervisor._close_pipes(process)

    @staticmethod
    def _close_pipes(process: subprocess.Popen) -> None:
        for stream in (process.stdin, process.stdout, process.stderr):
            try:
                if stream is not None:
                    stream.close()
            except OSError:
                pass

    def _cleanup(
        self, process: subprocess.Popen | None, threads: list[threading.Thread]
    ) -> None:
        if process is not None and process.poll() is None:
            self._terminate(process)
        self._join(threads, _JOIN_TIMEOUT)


class _StdinWriter(threading.Thread):
    def __init__(self, stream, payload: bytes) -> None:
        super().__init__(name="parser-stdin", daemon=True)
        self._stream = stream
        self._payload = payload
        self.failed = False

    def run(self) -> None:
        try:
            self._stream.write(self._payload)
            self._stream.flush()
        except (BrokenPipeError, OSError, ValueError):
            self.failed = True
        finally:
            try:
                self._stream.close()
            except (BrokenPipeError, OSError, ValueError):
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
            except (OSError, ValueError):
                pass


class _Drain(threading.Thread):
    """Consumes a bounded amount of stderr and discards its content."""

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
            except (OSError, ValueError):
                pass
