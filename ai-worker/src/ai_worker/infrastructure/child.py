"""Isolated child process entrypoint.

Reads a single framed request from stdin, parses, and writes one JSON envelope
to stdout. Every failure is converted to a stable, content-free error code; raw
document content, paths and SDK exceptions are never written out.
"""

from __future__ import annotations

import sys

from ai_worker import composition
from ai_worker.domain import errors
from ai_worker.infrastructure import protocol

_MAX_FRAME_BYTES = 12 * 1024 * 1024


def _emit(envelope: dict) -> None:
    sys.stdout.buffer.write(protocol.encode_envelope(envelope))
    sys.stdout.buffer.flush()


def _error(code: str) -> dict:
    return {"ok": False, "error": errors.ParseFailure(code).to_wire()}


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
