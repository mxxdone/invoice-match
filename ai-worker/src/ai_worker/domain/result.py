"""Pure, deterministic result types for ``document-parse-v1``.

These types carry no timestamps or random identifiers. Original numeric lexemes
and formula expressions are preserved as strings; nothing is evaluated.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class DocumentKind(str, Enum):
    PDF = "pdf"
    XLSX = "xlsx"


class CellType(str, Enum):
    STRING = "string"
    NUMBER = "number"
    BOOLEAN = "boolean"
    DATE = "date"
    FORMULA = "formula"
    ERROR = "error"


class WarningCode(str, Enum):
    EMPTY_TEXT_LAYER = "EMPTY_TEXT_LAYER"


@dataclass(frozen=True)
class ParseWarning:
    code: str
    message: str
    page: int | None = None


@dataclass(frozen=True)
class PdfPage:
    page: int
    text: str


@dataclass(frozen=True)
class PdfContent:
    page_count: int
    pages: tuple[PdfPage, ...]


@dataclass(frozen=True)
class Cell:
    """A single non-empty spreadsheet cell.

    ``value`` is always a JSON-safe scalar. Numeric lexemes and formula
    expressions are strings so that precision is never lost through float
    conversion. ``cached_value``/``cached_type`` describe a formula's cached
    result without evaluating it.
    """

    row: int
    column: int
    coordinate: str
    cell_type: str
    value: object
    cached_value: str | None = None
    cached_type: str | None = None
    number_format: str | None = None


@dataclass(frozen=True)
class SheetRow:
    row: int
    cells: tuple[Cell, ...]


@dataclass(frozen=True)
class Sheet:
    index: int
    name: str
    state: str
    rows: tuple[SheetRow, ...]


@dataclass(frozen=True)
class SpreadsheetContent:
    sheet_count: int
    sheets: tuple[Sheet, ...]


@dataclass(frozen=True)
class SourceMetadata:
    media_type: str
    size_bytes: int
    sha256: str


@dataclass(frozen=True)
class DocumentParseResult:
    schema_version: str
    parser_version: str
    document_id: str
    source: SourceMetadata
    kind: str
    warnings: tuple[ParseWarning, ...]
    pdf: PdfContent | None = None
    spreadsheet: SpreadsheetContent | None = None


__all__ = [
    "DocumentKind",
    "CellType",
    "WarningCode",
    "ParseWarning",
    "PdfPage",
    "PdfContent",
    "Cell",
    "SheetRow",
    "Sheet",
    "SpreadsheetContent",
    "SourceMetadata",
    "DocumentParseResult",
]
