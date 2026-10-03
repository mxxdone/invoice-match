"""Isolated child process entrypoint.

The OS address-space limit is applied here, before any parser SDK is imported,
so the memory ceiling is in place before pypdf/defusedxml allocate. Only the
stdlib, domain error codes and the tiny framing module are imported first.

Every failure is converted to a stable, content-free error code; raw document
content, paths and SDK exceptions are never written out.
"""

from __future__ import annotations

import os
import sys

from ai_worker.domain import errors
from ai_worker.infrastructure import protocol

_MAX_FRAME_BYTES = 12 * 1024 * 1024
_DEFAULT_MEMORY_BYTES = 512 * 1024 * 1024


def apply_process_memory_limit(memory_bytes: int) -> None:
    """Cap this process's address space. Linux/posix only; fails closed."""
    if os.name != "posix":
        raise errors.ParseFailure(errors.UNSUPPORTED_HOST)
    import resource

    try:
        resource.setrlimit(resource.RLIMIT_AS, (memory_bytes, memory_bytes))
    except (ValueError, OSError) as exc:
        raise errors.ParseFailure(errors.UNSUPPORTED_HOST) from exc


def _emit(envelope: dict) -> None:
    sys.stdout.buffer.write(protocol.encode_envelope(envelope))
    sys.stdout.buffer.flush()


def _error(code: str) -> dict:
    return {"ok": False, "error": errors.ParseFailure(code).to_wire()}


def _memory_bytes_from_header(header: dict) -> int:
    limits = header.get("limits")
    if isinstance(limits, dict):
        value = limits.get("memory_bytes")
        if isinstance(value, int) and value > 0:
            return value
    return _DEFAULT_MEMORY_BYTES


def main() -> int:
    payload = sys.stdin.buffer.read(_MAX_FRAME_BYTES + 1)
    if len(payload) > _MAX_FRAME_BYTES:
        _emit(_error(errors.INPUT_TOO_LARGE))
        return 0
    try:
        header, data = protocol.decode_request(payload)
    except Exception:  # noqa: BLE001 - malformed frame is not caller-facing
        _emit(_error(errors.INTERNAL_ERROR))
        return 0

    try:
        apply_process_memory_limit(_memory_bytes_from_header(header))
    except errors.ParseFailure as exc:
        _emit({"ok": False, "error": exc.to_wire()})
        return 1
    except Exception:  # noqa: BLE001 - never leak the underlying failure
        _emit(_error(errors.UNSUPPORTED_HOST))
        return 1

    # Import the parser stack only after the memory limit is in force.
    from ai_worker import composition

    try:
        result = composition.run_child_parse(header, data)
    except errors.ParseFailure as exc:
        _emit({"ok": False, "error": exc.to_wire()})
    except MemoryError:
        _emit(_error(errors.MEMORY_LIMIT_EXCEEDED))
    except Exception:  # noqa: BLE001 - never leak SDK exceptions
        _emit(_error(errors.INTERNAL_ERROR))
    else:
        _emit({"ok": True, "result": result})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
