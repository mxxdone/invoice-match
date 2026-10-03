"""Parse orchestration: pre-parse verification plus limit/failure policy."""

from __future__ import annotations

from dataclasses import dataclass

from ai_worker.application.limits import DEFAULT_LIMITS, ParseLimits
from ai_worker.application.ports import (
    FormatDetector,
    PdfParser,
    Sha256Hasher,
    SpreadsheetParser,
)
from ai_worker.domain import errors
from ai_worker.domain.result import (
    DocumentParseResult,
    DocumentKind,
    ParseWarning,
    SourceMetadata,
    WarningCode,
)
from ai_worker.version import PARSER_VERSION, SCHEMA_VERSION

_MEDIA_TYPE_BY_KIND = {
    DocumentKind.PDF.value: "application/pdf",
    DocumentKind.XLSX.value: (
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ),
}


@dataclass(frozen=True)
class ParseRequest:
    """Server-confirmed document identity supplied to the parser."""

    document_id: str
    media_type: str
    size_bytes: int
    sha256: str


def _base_media_type(media_type: str) -> str:
    return media_type.split(";", 1)[0].strip().lower()


class ParseDocumentService:
    def __init__(
        self,
        detector: FormatDetector,
        hasher: Sha256Hasher,
        pdf_parser: PdfParser,
        spreadsheet_parser: SpreadsheetParser,
        limits: ParseLimits = DEFAULT_LIMITS,
    ) -> None:
        self._detector = detector
        self._hasher = hasher
        self._pdf_parser = pdf_parser
        self._spreadsheet_parser = spreadsheet_parser
        self._limits = limits

    @property
    def limits(self) -> ParseLimits:
        return self._limits

    def verify_metadata(self, request: ParseRequest, data: bytes) -> str:
        """Verify byte metadata without opening an untrusted file container."""
        limits = self._limits

        if request.size_bytes > limits.max_input_bytes:
            raise errors.ParseFailure(errors.INPUT_TOO_LARGE)
        if len(data) != request.size_bytes:
            raise errors.ParseFailure(errors.SIZE_MISMATCH)
        if len(data) > limits.max_input_bytes:
            raise errors.ParseFailure(errors.INPUT_TOO_LARGE)

        actual_sha256 = self._hasher.digest(data)
        if actual_sha256.lower() != request.sha256.strip().lower():
            raise errors.ParseFailure(errors.CHECKSUM_MISMATCH)
        return actual_sha256.lower()

    def verify_source(
        self, request: ParseRequest, data: bytes
    ) -> tuple[str, SourceMetadata]:
        """Verify metadata and detect the format inside the isolated parser."""
        actual_sha256 = self.verify_metadata(request, data)

        kind = self._detector.detect(data)
        if kind is None:
            raise errors.ParseFailure(errors.FORMAT_UNSUPPORTED)

        expected_media_type = _MEDIA_TYPE_BY_KIND.get(kind)
        if _base_media_type(request.media_type) != expected_media_type:
            raise errors.ParseFailure(errors.FORMAT_MISMATCH)

        return kind, SourceMetadata(
            media_type=expected_media_type,
            size_bytes=len(data),
            sha256=actual_sha256.lower(),
        )

    def parse(self, request: ParseRequest, data: bytes) -> DocumentParseResult:
        kind, source = self.verify_source(request, data)
        limits = self._limits

        if kind == DocumentKind.PDF.value:
            pdf = self._pdf_parser.parse(data, limits)
            warnings = self._pdf_warnings(pdf.pages)
            return DocumentParseResult(
                schema_version=SCHEMA_VERSION,
                parser_version=PARSER_VERSION,
                document_id=request.document_id,
                source=source,
                kind=kind,
                warnings=warnings,
                pdf=pdf,
            )

        spreadsheet = self._spreadsheet_parser.parse(data, limits)
        return DocumentParseResult(
            schema_version=SCHEMA_VERSION,
            parser_version=PARSER_VERSION,
            document_id=request.document_id,
            source=source,
            kind=kind,
            warnings=(),
            spreadsheet=spreadsheet,
        )

    @staticmethod
    def _pdf_warnings(pages) -> tuple[ParseWarning, ...]:
        warnings: list[ParseWarning] = []
        for page in pages:
            if page.text.strip() == "":
                warnings.append(
                    ParseWarning(
                        code=WarningCode.EMPTY_TEXT_LAYER.value,
                        message=(
                            f"Page {page.page} has an empty text layer; "
                            "it may be an image and was not OCRed."
                        ),
                        page=page.page,
                    )
                )
        return tuple(warnings)
