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


def consume_proposals() -> None:
    _consume_advisory(None)


def consume_graphs(segment: str) -> None:
    if segment not in {"start","resume"}:
        from ai_worker.application.execution import WorkerFailure
        raise WorkerFailure("INVALID_CONFIGURATION")
    _consume_advisory(segment)


def _consume_advisory(graph_segment) -> None:
    from ai_worker.application.execution import WorkerFailure
    from ai_worker.application.proposal_execution import ProcessProposal, ProposalRequest, execution_plan
    from ai_worker.application.recognition import RecognizeInvoice
    from ai_worker.domain.advisory import AdvisoryFailure
    from ai_worker.infrastructure.proposal_core_client import ProposalCoreClient
    from ai_worker.infrastructure.structured_model import ChatStructuredModel
    from ai_worker.infrastructure.azure_invoice import AzureInvoiceRecognizer
    from ai_worker.infrastructure.rabbit_consumer import RabbitConsumer
    if not sys.platform.startswith("linux"):raise WorkerFailure("UNSUPPORTED_HOST")
    def required(name):
        value=os.environ.get(name,"")
        if not value or any(ord(c)<32 for c in value):raise WorkerFailure("INVALID_CONFIGURATION")
        return value
    if os.environ.get("ANALYSIS_GRAPH_ENABLED" if graph_segment else "ANALYSIS_AI_ENABLED")!="true":raise WorkerFailure("INVALID_CONFIGURATION")
    try:
        url,model=required("AI_MODEL_URL"),required("AI_MODEL_NAME")
        parameter=os.environ.get("AI_MODEL_TOKEN_PARAMETER","max_completion_tokens")
        plan=execution_plan(url,model,required("AI_INPUT_PRICE_PER_MILLION"),required("AI_OUTPUT_PRICE_PER_MILLION"),required("AI_COST_CURRENCY"),required("AI_COST_CEILING"),parameter)
        adapter=ChatStructuredModel(url,required("AI_MODEL_KEY"),model,token_parameter=parameter,api_key_header=os.environ.get("AI_MODEL_API_KEY_HEADER")=="true")
        if graph_segment:
            from ai_worker.infrastructure.graph_core_client import GraphCoreClient
            core=GraphCoreClient(required("CORE_API_URL"),required("ANALYSIS_WORKER_TOKEN"))
        else:core=ProposalCoreClient(required("CORE_API_URL"),required("ANALYSIS_WORKER_TOKEN"))
        embedding=None
        if any(os.environ.get(n) for n in ("AI_EMBEDDING_URL","AI_EMBEDDING_KEY","AI_EMBEDDING_MODEL","AI_EMBEDDING_VERSION","AI_EMBEDDING_DIMENSION","AI_EMBEDDING_PRICE_PER_MILLION")):
            from ai_worker.infrastructure.embedding_model import EmbeddingModel
            from decimal import Decimal
            from hashlib import sha256
            embed_url=required("AI_EMBEDDING_URL");dimension=int(required("AI_EMBEDDING_DIMENSION"));price=Decimal(required("AI_EMBEDDING_PRICE_PER_MILLION"))
            if not price.is_finite() or price<0 or price>1000000 or price.as_tuple().exponent < -8:raise AdvisoryFailure("AI_CONFIGURATION")
            embedding=EmbeddingModel(embed_url,required("AI_EMBEDDING_KEY"),required("AI_EMBEDDING_MODEL"),required("AI_EMBEDDING_VERSION"),dimension)
            plan["embedding"]={"model":embedding.model,"version":embedding.version,"dimension":dimension,"providerFingerprint":sha256(embed_url.encode()).hexdigest(),"pricePerMillion":float(price)}
        recognizer=None
        if os.environ.get("AZURE_DOCUMENT_ENDPOINT") or os.environ.get("AZURE_DOCUMENT_KEY"):
            if os.environ.get("AZURE_DOCUMENT_TIER")!="F0":raise AdvisoryFailure("OCR_CONFIGURATION")
            recognizer=RecognizeInvoice(AzureInvoiceRecognizer(required("AZURE_DOCUMENT_ENDPOINT"),required("AZURE_DOCUMENT_KEY")))
        settings={"host":required("ANALYSIS_RABBIT_HOST"),"port":os.environ.get("ANALYSIS_RABBIT_PORT","5672"),
            "vhost":os.environ.get("ANALYSIS_RABBIT_VHOST","/"),"username":required("ANALYSIS_RABBIT_USERNAME"),"password":required("ANALYSIS_RABBIT_PASSWORD"),
            "exchange":"invoice.proposal","queue":"invoice.proposal.requests","routing_key":"ai-review-v1"}
        if not 1<=int(settings["port"])<=65535:raise WorkerFailure("INVALID_CONFIGURATION")
        if graph_segment:
            from ai_worker.application.graph_execution import GraphRequest
            settings.update(exchange="invoice.graph",queue="invoice.graph."+graph_segment,routing_key="ai-review-v2."+graph_segment)
            RabbitConsumer(build_graph_processor(core,adapter,plan,recognizer,embedding),settings,
                GraphRequest.decode if graph_segment=="start" else GraphRequest.decode_resume).run()
        else:RabbitConsumer(ProcessProposal(core,adapter,plan,recognizer,embedding),settings,ProposalRequest.decode).run()
    except AdvisoryFailure as exc:raise WorkerFailure(exc.code) from exc
    except WorkerFailure:raise
    except Exception as exc:raise WorkerFailure("WORKER_FAILED") from exc


def build_graph_processor(core, model, plan, recognizer=None, embedding=None):
    """SDK construction belongs to the composition root."""
    from ai_worker.application.graph_execution import ProcessGraph
    from ai_worker.infrastructure.graph_runtime import LangGraphRuntime
    return ProcessGraph(core, LangGraphRuntime(), model, plan, recognizer, embedding)


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
