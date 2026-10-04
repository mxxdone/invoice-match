"""SDK-independent graph policy, stages and delivery ownership."""
from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Protocol
from uuid import uuid4

from ai_worker.application.execution import WorkerFailure, strict_json, keys, uuid, digest, require_lease, Document
from ai_worker.application.graph_contract import GraphVersions
from ai_worker.application.document_agent import DocumentAgent, parser_segments
from ai_worker.application.item_mapping import ItemMappingAgent
from ai_worker.application.resolution import EvidenceAgent, ResolutionAgent, policy_query
from ai_worker.domain.advisory import AdvisoryFailure


@dataclass(frozen=True)
class GraphRequest:
    run_id: str
    context_hash: str
    event: dict | None = None

    @classmethod
    def decode_resume(cls, data, message_id, content_type, event_type, encoding):
        n=keys(strict_json(data,4096),{"schemaVersion","eventId","graphExecutionId","contextHash","workflowVersion","interruptId","reviewId","reviewVersion","checkpointId","checkpointHash"})
        if (content_type,event_type,encoding)!=("application/json","InvoiceGraphResumeRequested","UTF-8") \
                or n["schemaVersion"]!="graph-resume-request-v1" or n["workflowVersion"]!=GraphVersions().workflow \
                or n["eventId"]!=message_id or type(n["reviewVersion"]) is not int or n["reviewVersion"]!=1 \
                or type(n["interruptId"]) is not str or not 32<=len(n["interruptId"])<=64 \
                or any(c not in "0123456789abcdef" for c in n["interruptId"]):raise WorkerFailure("INVALID_MESSAGE")
        uuid(n["eventId"]);uuid(n["reviewId"]);uuid(n["checkpointId"]);digest(n["checkpointHash"])
        return cls(uuid(n["graphExecutionId"]),digest(n["contextHash"]),n)

    @classmethod
    def decode(cls, data, message_id, content_type, event_type, encoding):
        n = keys(strict_json(data, 4096), {"schemaVersion", "eventId", "graphExecutionId", "contextHash", "workflowVersion"})
        if (content_type, event_type, encoding) != ("application/json", "InvoiceGraphRequested", "UTF-8") \
                or n["schemaVersion"] != "graph-request-v1" or n["workflowVersion"] != GraphVersions().workflow \
                or n["eventId"] != message_id or message_id != n["graphExecutionId"]:
            raise WorkerFailure("INVALID_MESSAGE")
        return cls(uuid(n["graphExecutionId"]), digest(n["contextHash"]))


class GraphCore(Protocol):
    def claim(self, request) -> dict: ...
    def heartbeat(self, request, token) -> dict: ...
    def stages(self, request, token) -> list[dict]: ...
    def stage(self, request, token, name, payload) -> dict: ...
    def reserve(self, request, token, request_id, tokens) -> dict: ...
    def tool(self, request, token, arguments) -> dict: ...
    def policy(self, request, token, arguments) -> dict: ...
    def source(self, request, token, document) -> bytes: ...
    def checkpoint(self, request, token, checkpoint) -> dict: ...
    def read(self, request, token, checkpoint_id=None) -> dict: ...
    def writes(self, request, token, writes) -> list[dict]: ...
    def waiting(self, request, token, proof) -> dict: ...
    def complete(self, request, token) -> dict: ...
    def failure(self, request, token, code) -> dict: ...
    def defer(self, request) -> dict: ...
    def resume(self, request, token) -> dict: ...


class GraphRuntime(Protocol):
    def execute(self, session: GraphSession) -> dict: ...


def human_reasons(document, mapping):
    reasons = set()
    result = document["result"]
    if not result["fields"] and not result["lines"] or any(w in {"DOCUMENT_CONFLICT", "AMBIGUOUS_LAYOUT"} for w in result["warnings"]):
        reasons.add("DOCUMENT_REVIEW_REQUIRED")
    for line in mapping["result"]["lines"]:
        if not line["candidates"]: reasons.add("NO_ITEM_CANDIDATE")
        if len(line["candidates"]) > 1: reasons.add("AMBIGUOUS_ITEM")
    return sorted(reasons)


class GraphSession:
    """Frozen stage ledger survives a node replay even before the SDK checkpoint is saved."""
    def __init__(self, core, request, token, context, model, plan, stopping, recognizer=None, embedding=None,
                 wall_seconds=120, monotonic=time.monotonic):
        self.core, self.request, self.token, self.context = core, request, token, context
        self.model, self.plan, self.stopping = model, plan, stopping
        self.recognizer, self.embedding = recognizer, embedding
        self.monotonic, self.deadline = monotonic, monotonic() + wall_seconds
        self.resume = None
        if request.event is not None:
            self.resume=keys(core.resume(request,token),{"reviewRef","checkpointId","checkpointHash","interruptId","reviewVersion","confirmation"})
            for key,event_key in (("reviewRef","reviewId"),("checkpointId","checkpointId"),("checkpointHash","checkpointHash"),("interruptId","interruptId"),("reviewVersion","reviewVersion")):
                if self.resume[key]!=request.event[event_key]:raise WorkerFailure("INVALID_PROTOCOL")
            if not isinstance(self.resume["confirmation"],dict):raise WorkerFailure("INVALID_PROTOCOL")
        self.ledger = {}
        for saved in core.stages(request, token):
            keys(saved, {"ref", "stage", "hash", "payload"});uuid(saved["ref"]);digest(saved["hash"])
            if saved["stage"] in self.ledger or not isinstance(saved["payload"], dict):raise WorkerFailure("INVALID_PROTOCOL")
            self.ledger[saved["stage"]] = saved
        if len(self.ledger) > 32:raise WorkerFailure("GRAPH_LIMIT")
        # Restored/resumed graphs may skip the execution node entirely.
        if "execution" in self.ledger and self.saved("execution") != self.plan:raise AdvisoryFailure("AI_CONFIGURATION")

    def active(self):
        if self.stopping():raise WorkerFailure("SHUTTING_DOWN")
        if self.monotonic() >= self.deadline:raise WorkerFailure("GRAPH_LIMIT")
        require_lease(keys(self.core.heartbeat(self.request, self.token), {"leaseUntil"})["leaseUntil"])

    def before_call(self, tokens):
        self.active()
        if keys(self.core.reserve(self.request, self.token, str(uuid4()), tokens), {"reserved"})["reserved"] is not True:
            raise WorkerFailure("INVALID_PROTOCOL")

    def saved(self, name):return self.ledger[name]["payload"]

    def stage(self, name, produce):
        self.active()
        if name not in self.ledger:
            reply = keys(self.core.stage(self.request, self.token, name, produce()), {"ref", "stage", "hash", "payload"})
            uuid(reply["ref"]);digest(reply["hash"])
            if reply["stage"] != name or not isinstance(reply["payload"], dict):raise WorkerFailure("INVALID_PROTOCOL")
            self.ledger[name] = reply
        return self.ledger[name]["ref"]

    def execution(self):
        if "execution" in self.ledger and self.saved("execution") != self.plan:raise AdvisoryFailure("AI_CONFIGURATION")
        return self.stage("execution", lambda: self.plan)

    def document(self):
        def produce():
            for document in self.context["documents"]:
                parsed, stage = document["parsed"], "ocr:" + document["documentId"]
                if parsed["kind"] != "pdf" or any(p["text"].strip() for p in parsed["pdf"]["pages"]) or stage in self.ledger:continue
                if self.recognizer is None:raise AdvisoryFailure("OCR_CONFIGURATION")
                frozen = Document.decode(next(d for d in self.context["evidenceBundle"]["documents"] if d["documentId"] == document["documentId"]))
                if frozen.size > 4000000 or parsed["pdf"]["pageCount"] > 2:raise AdvisoryFailure("OCR_LIMIT")
                self.active();data = self.core.source(self.request, self.token, frozen)
                self.before_call(1)
                self.stage(stage, lambda: self.recognizer.execute(frozen.document_id, data, frozen.checksum, parsed["pdf"]["pageCount"]))
            ocr = {k[4:]:v["payload"] for k,v in self.ledger.items() if k.startswith("ocr:")}
            return DocumentAgent(self.model, self.before_call).execute(parser_segments(self.context, ocr))
        return self.stage("document", produce)

    def mapping(self):
        def produce():
            self.active()
            prior = self.core.tool(self.request, self.token, {"requestId":str(uuid4()), "tool":"get_prior_invoice_cases", "query":"", "limit":10})
            if prior.get("schemaVersion") != "ai-tool-v1":raise WorkerFailure("INVALID_PROTOCOL")
            return ItemMappingAgent(self.model, self.before_call).execute(self.saved("document"), self.context["items"], prior["result"])
        return self.stage("mapping", produce)

    def interruption(self):
        return {"graphExecutionId":self.request.run_id, "documentStageRef":self.ledger["document"]["ref"],
                "mappingStageRef":self.ledger["mapping"]["ref"], "reasonCodes":human_reasons(self.saved("document"), self.saved("mapping"))}

    def evidence(self):
        def produce():
            if not self.context["matchResult"]["normal"] and self.embedding is not None and self.context.get("policyDocuments"):
                def embed():
                    config = self.plan["embedding"]
                    if any(d["embeddingModel"] != config["model"] or d["embeddingVersion"] != config["version"] or d["embeddingDimension"] != config["dimension"] for d in self.context["policyDocuments"]):raise AdvisoryFailure("AI_CONFIGURATION")
                    query=policy_query(self.context);self.before_call(len(query.encode())+1)
                    return {"schemaVersion":"ai-query-embedding-v1", "query":query, "model":config["model"], "version":config["version"], "dimension":config["dimension"], **self.embedding.embed(query)}
                self.stage("embedding", embed)
            def retrieve(query):
                self.active();embedded = self.saved("embedding") if "embedding" in self.ledger else None
                response = self.core.policy(self.request, self.token, {"requestId":str(uuid4()), "query":query, "mode":"HYBRID" if embedded else "LEXICAL",
                    "embeddingModel":embedded["model"] if embedded else None, "embeddingVersion":embedded["version"] if embedded else None,
                    "embedding":embedded["embedding"] if embedded else None, "limit":5})
                self.ledger["tool:"+response["request"]["requestId"]] = {"payload":response}
                return response
            return EvidenceAgent(retrieve).execute(self.context)
        return self.stage("evidence", produce)

    def resolution(self):
        evidence = self.saved("evidence")["result"]
        proof = {"status":"NOT_REQUIRED", "result":[]} if evidence["status"] == "NOT_REQUIRED" else self.saved("tool:"+evidence["toolRequestId"])
        return self.stage("resolution", lambda: ResolutionAgent(self.model, self.before_call).execute(self.context, self.saved("document"), self.saved("mapping"), proof))


class ProcessGraph:
    def __init__(self, core:GraphCore, runtime:GraphRuntime, model, plan, recognizer=None, embedding=None):
        self.core,self.runtime,self.model,self.plan = core,runtime,model,plan
        self.recognizer,self.embedding = recognizer,embedding

    def process(self, request:GraphRequest, stopping):
        claim=keys(self.core.claim(request), {"disposition", "token", "leaseUntil", "context", "waiting"})
        disposition=claim["disposition"]
        if disposition in {"STALE", "ALREADY_FINISHED"}:return
        if disposition == "WAITING_HUMAN":
            self.wait_proof(claim["waiting"]);return
        if disposition == "BUSY":self.durable(self.core.defer(request));return
        if disposition != "CLAIMED":raise WorkerFailure("INVALID_PROTOCOL")
        token=uuid(claim["token"]);require_lease(claim["leaseUntil"])
        try:
            session=GraphSession(self.core,request,token,claim["context"],self.model,self.plan,stopping,self.recognizer,self.embedding)
            result=self.runtime.execute(session)
            if result["disposition"] == "WAITING_HUMAN":self.wait_proof(result["waiting"])
            elif result["disposition"] == "FINISHED":
                session.active();reply=keys(self.core.complete(request,token), {"disposition", "proposalId", "payloadHash"})
                if reply["disposition"] != "COMPLETED" or uuid(reply["proposalId"]) != request.run_id:raise WorkerFailure("INVALID_PROTOCOL")
                digest(reply["payloadHash"])
            else:raise WorkerFailure("INVALID_PROTOCOL")
        except (AdvisoryFailure, WorkerFailure) as exc:
            if exc.code in {"LEASE_CONFLICT","STALE_INPUT"}:self.durable(self.core.defer(request));return
            if exc.code not in {"GRAPH_SDK_FAILED", "GRAPH_LIMIT", "GRAPH_REPEATED_INTERRUPT", "AI_CONFIGURATION", "AI_SCHEMA_INVALID", "AI_BUDGET_EXHAUSTED", "AI_INPUT_LIMIT", "AI_TOOL_DENIED", "OCR_CONFIGURATION", "OCR_LIMIT", "OCR_INVALID_RESPONSE", "SOURCE_MISMATCH", "AI_RATE_LIMIT", "AI_TIMEOUT", "AI_FAILED", "OCR_RATE_LIMIT", "OCR_TIMEOUT", "OCR_FAILED", "CORE_UNAVAILABLE", "CORE_TRANSIENT", "INVALID_PROTOCOL"}:raise
            self.durable(self.core.failure(request,token,exc.code))

    @staticmethod
    def durable(reply):
        keys(reply,{"disposition","runStatus"})
        if reply["disposition"]!="CHECKPOINTED" or reply["runStatus"] not in {"QUEUED","RUNNING","WAITING_HUMAN","FAILED","STALE","COMPLETED"}:raise WorkerFailure("INVALID_PROTOCOL")

    @staticmethod
    def wait_proof(proof):
        keys(proof, {"interruptId", "checkpointId", "checkpointHash", "taskId", "writeVersion", "writeHash", "reviewVersion"})
        uuid(proof["checkpointId"]);uuid(proof["taskId"]);digest(proof["checkpointHash"]);digest(proof["writeHash"])
        if type(proof["interruptId"]) is not str or not 32 <= len(proof["interruptId"]) <= 64 or any(c not in "0123456789abcdef" for c in proof["interruptId"]) \
                or type(proof["writeVersion"]) is not int or proof["writeVersion"] < 1 or proof["reviewVersion"] != 1:raise WorkerFailure("INVALID_PROTOCOL")
