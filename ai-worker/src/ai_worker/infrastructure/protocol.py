"""Parent/child framing for the isolated parser process.

Wire format: ``MAGIC | uint32 header length (big endian) | header JSON | bytes``.
Only metadata and the raw document bytes cross the boundary; no filesystem
paths are exchanged.
"""

from __future__ import annotations

import json

MAGIC = b"IMPARSE1"


def encode_request(header: dict, data: bytes) -> bytes:
    header_bytes = json.dumps(
        header, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    return MAGIC + len(header_bytes).to_bytes(4, "big") + header_bytes + data


def decode_request(payload: bytes) -> tuple[dict, bytes]:
    if not payload.startswith(MAGIC):
        raise ValueError("bad frame magic")
    offset = len(MAGIC)
    if len(payload) < offset + 4:
        raise ValueError("truncated frame header")
    header_length = int.from_bytes(payload[offset : offset + 4], "big")
    offset += 4
    if len(payload) < offset + header_length:
        raise ValueError("truncated frame metadata")
    header = json.loads(payload[offset : offset + header_length].decode("utf-8"))
    offset += header_length
    return header, payload[offset:]


def encode_envelope(envelope: dict) -> bytes:
    return json.dumps(
        envelope, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
