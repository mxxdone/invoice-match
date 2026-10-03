"""Composition root: the only place that wires concrete adapters together.

Application code never imports pypdf, defusedxml or the process/OS adapters;
it receives them through the ports defined in :mod:`ai_worker.application.ports`.
"""

from __future__ import annotations

import json

from ai_worker.application.limits import DEFAULT_LIMITS, ParseLimits
from ai_worker.application.service import ParseDocumentService, ParseRequest
from ai_worker.application.wire import result_to_wire
from ai_worker.domain import errors
from ai_worker.domain.result import DocumentParseResult
from ai_worker.infrastructure.format_detect import MagicFormatDetector
from ai_worker.infrastructure.hashing import Sha256Hasher
from ai_worker.infrastructure.ooxml import SafeOoxmlSpreadsheetParser
from ai_worker.infrastructure.pdf_pypdf import PypdfTextParser
from ai_worker.infrastructure.process_supervisor import ProcessSupervisor


def build_service(limits: ParseLimits = DEFAULT_LIMITS) -> ParseDocumentService:
    return ParseDocumentService(
        detector=MagicFormatDetector(),
        hasher=Sha256Hasher(),
        pdf_parser=PypdfTextParser(),
        spreadsheet_parser=SafeOoxmlSpreadsheetParser(),
        limits=limits,
    )


def parse_in_process(
    request: ParseRequest, data: bytes, limits: ParseLimits = DEFAULT_LIMITS
) -> DocumentParseResult:
    return build_service(limits).parse(request, data)


def parse_isolated(
    request: ParseRequest, data: bytes, limits: ParseLimits = DEFAULT_LIMITS
) -> dict:
    header = {
        "documentId": request.document_id,
        "mediaType": request.media_type,
        "sizeBytes": request.size_bytes,
        "sha256": request.sha256,
        "limits": limits.to_wire(),
    }
    return ProcessSupervisor().run(header, data, limits)


def run_child_parse(header: dict, data: bytes) -> dict:
    """Executed inside the isolated child process."""
    limits = ParseLimits(**(header.get("limits") or {}))
    request = ParseRequest(
        document_id=header["documentId"],
        media_type=header["mediaType"],
        size_bytes=header["sizeBytes"],
        sha256=header["sha256"],
    )
    result = parse_in_process(request, data, limits)
    wire = result_to_wire(result)
    payload = json.dumps(
        wire, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    if len(payload) > limits.max_result_json_bytes:
        raise errors.ParseFailure(errors.RESULT_TOO_LARGE)
    return wire
