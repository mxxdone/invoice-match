"""Stable, leak-free parse failure types.

Every failure surfaces a stable ``code`` and a fixed safe message. Raw document
content, internal paths, SDK exceptions and secrets must never be attached to a
``ParseFailure``; callers only ever see :attr:`ParseFailure.code`.
"""

from __future__ import annotations

# --- Input / metadata -------------------------------------------------------
INPUT_TOO_LARGE = "INPUT_TOO_LARGE"
SIZE_MISMATCH = "SIZE_MISMATCH"
CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
FORMAT_UNSUPPORTED = "FORMAT_UNSUPPORTED"
FORMAT_MISMATCH = "FORMAT_MISMATCH"

# --- PDF --------------------------------------------------------------------
PDF_ENCRYPTED = "PDF_ENCRYPTED"
PDF_CORRUPT = "PDF_CORRUPT"
PDF_PAGE_LIMIT = "PDF_PAGE_LIMIT"

# --- XLSX / OOXML -----------------------------------------------------------
XLSX_ENCRYPTED = "XLSX_ENCRYPTED"
XLSX_CORRUPT = "XLSX_CORRUPT"
XLSX_SHEET_LIMIT = "XLSX_SHEET_LIMIT"
XLSX_DIMENSION_LIMIT = "XLSX_DIMENSION_LIMIT"
XLSX_CELL_LIMIT = "XLSX_CELL_LIMIT"

# --- ZIP container ----------------------------------------------------------
ZIP_ENTRY_LIMIT = "ZIP_ENTRY_LIMIT"
ZIP_ENTRY_TOO_LARGE = "ZIP_ENTRY_TOO_LARGE"
ZIP_TOTAL_TOO_LARGE = "ZIP_TOTAL_TOO_LARGE"
ZIP_COMPRESSION_RATIO = "ZIP_COMPRESSION_RATIO"
ZIP_ENCRYPTED_ENTRY = "ZIP_ENCRYPTED_ENTRY"
ZIP_DUPLICATE_ENTRY = "ZIP_DUPLICATE_ENTRY"
ZIP_UNSAFE_PATH = "ZIP_UNSAFE_PATH"

# --- XML --------------------------------------------------------------------
XML_UNSAFE = "XML_UNSAFE"

# --- Output / runtime -------------------------------------------------------
TEXT_VALUE_LIMIT = "TEXT_VALUE_LIMIT"
RESULT_TOO_LARGE = "RESULT_TOO_LARGE"
TIMEOUT = "TIMEOUT"
MEMORY_LIMIT_EXCEEDED = "MEMORY_LIMIT_EXCEEDED"
OUTPUT_LIMIT_EXCEEDED = "OUTPUT_LIMIT_EXCEEDED"
PARSER_CRASHED = "PARSER_CRASHED"
INTERNAL_ERROR = "INTERNAL_ERROR"
UNSUPPORTED_HOST = "UNSUPPORTED_HOST"

_SAFE_MESSAGES: dict[str, str] = {
    INPUT_TOO_LARGE: "Input exceeds the maximum allowed size.",
    SIZE_MISMATCH: "Declared size does not match the received bytes.",
    CHECKSUM_MISMATCH: "Declared SHA-256 does not match the received bytes.",
    FORMAT_UNSUPPORTED: "File format is not supported.",
    FORMAT_MISMATCH: "File signature does not match the declared media type.",
    PDF_ENCRYPTED: "Encrypted PDF documents are not supported.",
    PDF_CORRUPT: "The PDF document could not be read.",
    PDF_PAGE_LIMIT: "The PDF exceeds the page limit.",
    XLSX_ENCRYPTED: "Encrypted workbooks are not supported.",
    XLSX_CORRUPT: "The workbook could not be read.",
    XLSX_SHEET_LIMIT: "The workbook exceeds the sheet limit.",
    XLSX_DIMENSION_LIMIT: "A cell address exceeds the row or column limit.",
    XLSX_CELL_LIMIT: "The workbook exceeds the non-empty cell limit.",
    ZIP_ENTRY_LIMIT: "The archive exceeds the entry limit.",
    ZIP_ENTRY_TOO_LARGE: "An archive entry exceeds the per-entry size limit.",
    ZIP_TOTAL_TOO_LARGE: "The archive exceeds the total decompressed size limit.",
    ZIP_COMPRESSION_RATIO: "An archive entry exceeds the compression ratio limit.",
    ZIP_ENCRYPTED_ENTRY: "Encrypted archive entries are not supported.",
    ZIP_DUPLICATE_ENTRY: "The archive contains duplicate entry names.",
    ZIP_UNSAFE_PATH: "The archive contains an unsafe entry path.",
    XML_UNSAFE: "The document contains unsafe XML constructs.",
    TEXT_VALUE_LIMIT: "Extracted text and values exceed the size limit.",
    RESULT_TOO_LARGE: "The parse result exceeds the JSON size limit.",
    TIMEOUT: "Parsing exceeded the time limit.",
    MEMORY_LIMIT_EXCEEDED: "Parsing exceeded the memory limit.",
    OUTPUT_LIMIT_EXCEEDED: "The parser produced more output than allowed.",
    PARSER_CRASHED: "The parser process failed.",
    INTERNAL_ERROR: "The parser encountered an internal error.",
    UNSUPPORTED_HOST: "This host cannot enforce parser resource limits.",
}


class ParseFailure(Exception):
    """A typed, content-free parser failure."""

    def __init__(self, code: str, message: str | None = None) -> None:
        self.code = code
        self.safe_message = message or _SAFE_MESSAGES.get(code, _SAFE_MESSAGES[INTERNAL_ERROR])
        super().__init__(self.safe_message)

    def to_wire(self) -> dict[str, str]:
        return {"code": self.code, "message": self.safe_message}


__all__ = [
    "ParseFailure",
    "INPUT_TOO_LARGE",
    "SIZE_MISMATCH",
    "CHECKSUM_MISMATCH",
    "FORMAT_UNSUPPORTED",
    "FORMAT_MISMATCH",
    "PDF_ENCRYPTED",
    "PDF_CORRUPT",
    "PDF_PAGE_LIMIT",
    "XLSX_ENCRYPTED",
    "XLSX_CORRUPT",
    "XLSX_SHEET_LIMIT",
    "XLSX_DIMENSION_LIMIT",
    "XLSX_CELL_LIMIT",
    "ZIP_ENTRY_LIMIT",
    "ZIP_ENTRY_TOO_LARGE",
    "ZIP_TOTAL_TOO_LARGE",
    "ZIP_COMPRESSION_RATIO",
    "ZIP_ENCRYPTED_ENTRY",
    "ZIP_DUPLICATE_ENTRY",
    "ZIP_UNSAFE_PATH",
    "XML_UNSAFE",
    "TEXT_VALUE_LIMIT",
    "RESULT_TOO_LARGE",
    "TIMEOUT",
    "MEMORY_LIMIT_EXCEEDED",
    "OUTPUT_LIMIT_EXCEEDED",
    "PARSER_CRASHED",
    "INTERNAL_ERROR",
    "UNSUPPORTED_HOST",
]
