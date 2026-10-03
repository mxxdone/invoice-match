"""Single-delivery policy. SDKs, message acknowledgement and threads live outside."""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol
from uuid import UUID

from ai_worker.domain.errors import ParseFailure, is_known_code


class WorkerFailure(Exception):
    """Safe fixed code only; never include SDK details or document contents."""
    def __init__(self, code: str = "WORKER_FAILED") -> None:
        self.code = code
        super().__init__(code)


def strict_json(data: bytes, cap: int) -> dict:
    def finite(number):
        value = float(number)
        if not math.isfinite(value):
            raise ValueError("nonfinite number")
        return value
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result
    try:
        if len(data) > cap:
            raise ValueError("limit")
        value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                           parse_float=finite,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError("constant")))
        if not isinstance(value, dict):
            raise ValueError("object")
        return value
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise WorkerFailure("INVALID_PROTOCOL") from exc


def keys(value: object, expected: set[str]) -> dict:
    if not isinstance(value, dict) or set(value) != expected:
        raise WorkerFailure("INVALID_PROTOCOL")
    return value


def uuid(value: object) -> str:
    try:
        if not isinstance(value, str) or str(UUID(value)) != value:
            raise ValueError()
        return value
    except (ValueError, AttributeError) as exc:
        raise WorkerFailure("INVALID_PROTOCOL") from exc


def digest(value: object) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise WorkerFailure("INVALID_PROTOCOL")
    return value


def integer(value: object, low: int, high: int) -> int:
    if type(value) is not int or not low <= value <= high:
        raise WorkerFailure("INVALID_PROTOCOL")
    return value


@dataclass(frozen=True)
class Document:
    document_id: str
    revision_id: str
    file_name: str
    media_type: str
    size: int
    checksum: str

    @classmethod
    def decode(cls, value: object) -> Document:
        node = keys(value, {"documentId", "sourceDraftRevisionId", "fileName", "mediaType", "sizeBytes", "checksum"})
        name = node["fileName"]
        media = node["mediaType"]
        if not isinstance(name, str) or not 1 <= len(name) <= 255 or any(ord(c) < 32 or c in "/\\" for c in name):
            raise WorkerFailure("INVALID_PROTOCOL")
        if media not in {"application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}:
            raise WorkerFailure("INVALID_PROTOCOL")
        return cls(uuid(node["documentId"]), uuid(node["sourceDraftRevisionId"]), name, media,
                   integer(node["sizeBytes"], 1, 10 * 1024 * 1024), digest(node["checksum"]))


def documents(value: object) -> tuple[Document, ...]:
    if not isinstance(value, list) or not 1 <= len(value) <= 10:
        raise WorkerFailure("INVALID_PROTOCOL")
    result = tuple(Document.decode(item) for item in value)
    ids = [document.document_id for document in result]
    if len(set(ids)) != len(ids) or ids != sorted(ids):
        raise WorkerFailure("INVALID_PROTOCOL")
    return result


@dataclass(frozen=True)
class Request:
    event_id: str
    run_id: str
    case_id: str
    bundle_id: str
    version: int
    evidence_hash: str
    workflow: str
    documents: tuple[Document, ...]

    @classmethod
    def decode(cls, data: bytes, message_id: str, content_type: str, event_type: str,
               encoding: str) -> Request:
        node = keys(strict_json(data, 64 * 1024), {"eventId", "analysisRunId", "invoiceCaseId",
                    "evidenceBundleId", "inputVersion", "evidencePayloadHash", "workflowVersion", "documents"})
        if content_type != "application/json" or event_type != "InvoiceAnalysisRequested" or encoding != "UTF-8":
            raise WorkerFailure("INVALID_MESSAGE")
        if message_id != node["eventId"] or node["workflowVersion"] != "document-parser-v1":
            raise WorkerFailure("INVALID_MESSAGE")
        return cls(uuid(node["eventId"]), uuid(node["analysisRunId"]), uuid(node["invoiceCaseId"]),
                   uuid(node["evidenceBundleId"]), integer(node["inputVersion"], 1, 2147483647),
                   digest(node["evidencePayloadHash"]), node["workflowVersion"], documents(node["documents"]))

    def input(self) -> dict:
        return {"inputVersion": self.version, "evidencePayloadHash": self.evidence_hash}


class Core(Protocol):
    def claim(self, request: Request) -> dict: ...
    def heartbeat(self, request: Request, token: str) -> dict: ...
    def source(self, request: Request, token: str, document: Document) -> bytes: ...
    def result(self, request: Request, token: str, document: Document, payload: dict) -> dict: ...
    def failure(self, request: Request, token: str, code: str) -> dict: ...
    def defer(self, request: Request) -> dict: ...


class Parser(Protocol):
    def parse(self, document: Document, data: bytes) -> dict: ...


def require_lease(value: object) -> None:
    try:
        if not isinstance(value, str):
            raise ValueError()
        until = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if until.tzinfo is None or (until - datetime.now(timezone.utc)).total_seconds() < 70:
            raise ValueError()
    except (ValueError, OverflowError) as exc:
        raise WorkerFailure("LEASE_TOO_SHORT") from exc


class ProcessDelivery:
    def __init__(self, core: Core, parser: Parser) -> None:
        self.core, self.parser = core, parser

    def process(self, request: Request, stopping) -> None:
        """Return only with durable terminal evidence. Otherwise raise, preserving delivery."""
        def active():
            if stopping():
                raise WorkerFailure("SHUTTING_DOWN")
        active()
        claim = self.core.claim(request)
        disposition = claim.get("disposition")
        if disposition in {"STALE", "ALREADY_FINISHED"}:
            keys(claim, {"disposition"})
            return
        if disposition == "BUSY":
            keys(claim, {"disposition", "leaseUntil"})
            self._checkpoint(self.core.defer(request))
            return
        keys(claim, {"disposition", "claimToken", "leaseUntil", "invoiceCaseId", "evidenceBundleId",
                     "inputVersion", "evidencePayloadHash", "workflowVersion", "documents"})
        if disposition != "CLAIMED" or (claim["invoiceCaseId"], claim["evidenceBundleId"],
                claim["inputVersion"], claim["evidencePayloadHash"], claim["workflowVersion"]) != (
                request.case_id, request.bundle_id, request.version, request.evidence_hash, request.workflow):
            raise WorkerFailure("CLAIM_MISMATCH")
        integer(claim["inputVersion"], 1, 2147483647)
        frozen = documents(claim["documents"])
        if frozen != request.documents:
            raise WorkerFailure("CLAIM_MISMATCH")
        token = uuid(claim["claimToken"])
        try:
            self._documents(request, frozen, token, claim["leaseUntil"], active)
        except WorkerFailure as exc:
            if exc.code == "SHUTTING_DOWN":
                raise
            active()
            self._checkpoint(self.core.failure(request, token, exc.code))

    def _checkpoint(self, reply: dict) -> None:
        keys(reply, {"disposition", "runStatus"})
        if reply["disposition"] != "CHECKPOINTED" or reply["runStatus"] not in {
                "QUEUED", "RUNNING", "RETRY_SCHEDULED", "DEAD_LETTERED", "STALE", "COMPLETED", "FAILED"}:
            raise WorkerFailure("INVALID_PROTOCOL")

    def _documents(self, request, frozen, token, lease, active):
        require_lease(lease)
        for index, document in enumerate(frozen):
            active()
            heartbeat = keys(self.core.heartbeat(request, token), {"leaseUntil"})
            require_lease(heartbeat["leaseUntil"])
            data = self.core.source(request, token, document)
            active()
            try:
                parsed = self.parser.parse(document, data)
                payload = {"outcome": "SUCCESS", "result": parsed, "errorCode": None}
            except ParseFailure as exc:
                if not is_known_code(exc.code):
                    raise WorkerFailure("INVALID_PARSER_FAILURE") from exc
                payload = {"outcome": "FAILURE", "result": None, "errorCode": exc.code}
            active()
            reply = self.core.result(request, token, document, payload)
            if reply.get("disposition") == "STALE":
                keys(reply, {"disposition"})
                return
            keys(reply, {"disposition", "runStatus"})
            if reply["disposition"] not in {"ACCEPTED", "REPLAYED"}:
                raise WorkerFailure("INVALID_PROTOCOL")
            final = index == len(frozen) - 1
            if final and reply["runStatus"] in {"COMPLETED", "FAILED"}:
                return
            if reply["runStatus"] != "RUNNING" or final:
                raise WorkerFailure("INVALID_PROTOCOL")
        raise WorkerFailure("NO_TERMINAL_RESULT")
