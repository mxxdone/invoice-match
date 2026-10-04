"""Advisory failures never contain source text, credentials or SDK messages."""
from dataclasses import dataclass


ERROR_CODES = frozenset({
    "OCR_LIMIT", "OCR_CONFIGURATION", "OCR_RATE_LIMIT", "OCR_TIMEOUT", "OCR_FAILED",
    "OCR_INVALID_RESPONSE", "SOURCE_MISMATCH", "AI_CONFIGURATION", "AI_RATE_LIMIT",
    "AI_TIMEOUT", "AI_FAILED", "AI_SCHEMA_INVALID", "AI_BUDGET_EXHAUSTED",
    "AI_INPUT_LIMIT", "AI_INSUFFICIENT_EVIDENCE", "AI_TOOL_DENIED", "AI_CONFLICT",
})


class AdvisoryFailure(Exception):
    def __init__(self, code: str):
        if code not in ERROR_CODES:
            code = "AI_FAILED"
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class SourceSegment:
    segment_id: str
    document_id: str
    text: str
    origin: str
    page: int | None = None
    sheet: int | None = None
    cell: str | None = None

