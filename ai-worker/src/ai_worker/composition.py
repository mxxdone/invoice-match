"""Composition root: the only place that wires concrete adapters together.

Application code never imports pypdf, defusedxml or the process/OS adapters;
it receives them through the ports defined in :mod:`ai_worker.application.ports`.
"""

from __future__ import annotations

import os
import sys

from ai_worker.application.limits import DEFAULT_LIMITS, ParseLimits
from ai_worker.application.service import ParseDocumentService, ParseRequest
from ai_worker.application.wire import bounded_result_to_wire
from ai_worker.domain.result import DocumentParseResult
from ai_worker.infrastructure.format_detect import MagicFormatDetector
from ai_worker.infrastructure.hashing import Sha256Hasher
from ai_worker.infrastructure.ooxml import SafeOoxmlSpreadsheetParser
from ai_worker.infrastructure.pdf_pypdf import PypdfTextParser
from ai_worker.infrastructure.process_supervisor import ProcessSupervisor


def consume() -> None:
    from ai_worker.application.execution import ProcessDelivery, WorkerFailure
    if not sys.platform.startswith("linux"):
        raise WorkerFailure("UNSUPPORTED_HOST")
    from ai_worker.infrastructure.core_client import CoreClient
    from ai_worker.infrastructure.isolated_parser import IsolatedParser
    from ai_worker.infrastructure.rabbit_consumer import RabbitConsumer
    def required(name):
        value = os.environ.get(name, "")
        if not value or any(ord(c) < 32 for c in value):
            raise WorkerFailure("INVALID_CONFIGURATION")
        return value
    core = CoreClient(required("CORE_API_URL"), required("ANALYSIS_WORKER_TOKEN"))
    settings = {"host": required("ANALYSIS_RABBIT_HOST"),
                "username": required("ANALYSIS_RABBIT_USERNAME"),
                "password": required("ANALYSIS_RABBIT_PASSWORD"),
                "port": os.environ.get("ANALYSIS_RABBIT_PORT", "5672"),
                "vhost": os.environ.get("ANALYSIS_RABBIT_VHOST", "/"),
                "exchange": os.environ.get("ANALYSIS_RABBIT_EXCHANGE", "invoice.analysis"),
                "queue": os.environ.get("ANALYSIS_RABBIT_QUEUE", "invoice.analysis.requests"),
                "routing_key": os.environ.get("ANALYSIS_RABBIT_ROUTING_KEY", "document-parser-v1")}
    try:
        if not 1 <= int(settings["port"]) <= 65535:
            raise ValueError()
    except ValueError as exc:
        raise WorkerFailure("INVALID_CONFIGURATION") from exc
    try:
        RabbitConsumer(ProcessDelivery(core, IsolatedParser()), settings).run()
    except WorkerFailure:
        raise
    except Exception as exc:
        raise WorkerFailure("WORKER_FAILED") from exc


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
    return bounded_result_to_wire(result, limits.max_result_json_bytes)
