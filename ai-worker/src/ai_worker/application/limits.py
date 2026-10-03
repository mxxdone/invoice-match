"""Bounded-resource policy.

Raising any of these values is a contract change. The defaults mirror Plan
P2-04 exactly.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

from ai_worker.domain import errors

MIB = 1024 * 1024


@dataclass(frozen=True)
class ParseLimits:
    """All parser resource bounds in one immutable value."""

    max_input_bytes: int = 10 * MIB
    max_pdf_pages: int = 100
    max_sheets: int = 20
    max_rows_per_sheet: int = 10_000
    max_columns_per_sheet: int = 256
    max_nonempty_cells: int = 100_000
    max_zip_entries: int = 1_000
    max_zip_total_uncompressed_bytes: int = 50 * MIB
    max_zip_entry_uncompressed_bytes: int = 10 * MIB
    # Applied to every non-empty entry, regardless of size.
    max_compression_ratio: int = 100
    max_text_value_bytes: int = 1 * MIB
    max_result_json_bytes: int = 4 * MIB
    wall_seconds: float = 20.0
    memory_bytes: int = 512 * MIB

    def to_wire(self) -> dict[str, object]:
        return asdict(self)


DEFAULT_LIMITS = ParseLimits()


class Utf8Budget:
    """Tracks the UTF-8 byte budget for extracted text/values.

    Fails with ``TEXT_VALUE_LIMIT`` as soon as the budget is exceeded instead of
    silently truncating a successful result.
    """

    def __init__(self, max_bytes: int) -> None:
        self._max_bytes = max_bytes
        self._used = 0

    @property
    def used(self) -> int:
        return self._used

    def add(self, value: str) -> None:
        self._used += len(value.encode("utf-8"))
        if self._used > self._max_bytes:
            raise errors.ParseFailure(errors.TEXT_VALUE_LIMIT)
