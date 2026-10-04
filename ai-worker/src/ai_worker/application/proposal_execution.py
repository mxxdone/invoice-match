"""Finite advisory workflow. Only Core checkpoint proofs permit delivery acknowledgement."""
from __future__ import annotations
import json
import time
from decimal import Decimal, InvalidOperation
from hashlib import sha256
from typing import Protocol
from dataclasses import dataclass
from uuid import uuid4
from ai_worker.application.execution import WorkerFailure, strict_json, keys, uuid, digest, require_lease, Document
from ai_worker.application.document_agent import DocumentAgent, parser_segments, PROMPT_VERSION, StructuredModel
from ai_worker.application.item_mapping import ItemMappingAgent
from ai_worker.application.recognition import RecognizeInvoice
from ai_worker.application.resolution import EvidenceAgent, ResolutionAgent, policy_query
from ai_worker.domain.advisory import AdvisoryFailure

@dataclass(frozen=True)
class ProposalRequest:
    run_id: str
    context_hash: str
    @classmethod
    def decode(cls,data:bytes,message_id:str,content_type:str,event_type:str,encoding:str):
        n=keys(strict_json(data,4096),{"schemaVersion","eventId","proposalRunId","contextHash","workflowVersion"})
        if content_type!="application/json" or event_type!="InvoiceProposalRequested" or encoding!="UTF-8" \
            or n["schemaVersion"]!="ai-request-v1" or n["workflowVersion"]!="ai-review-v1" \
            or message_id!=n["eventId"] or n["eventId"]!=n["proposalRunId"]:
            raise WorkerFailure("INVALID_MESSAGE")
        return cls(uuid(n["proposalRunId"]),digest(n["contextHash"]))

class ProposalCore(Protocol):
    def claim(self,request:ProposalRequest)->dict: ...
    def heartbeat(self,request,token)->dict: ...
    def defer(self,request)->dict: ...
    def checkpoint(self,request,token,stage,payload)->dict: ...
    def reserve(self,request,token,request_id,tokens)->dict: ...
    def tool(self,request,token,args)->dict: ...
    def policy(self,request,token,args)->dict: ...
    def source(self,request,token,document)->bytes: ...
    def complete(self,request,token)->dict: ...
    def failure(self,request,token,code)->dict: ...


def execution_plan(url:str,model:str,input_price:str,output_price:str,currency:str,ceiling:str,token_parameter:str)->dict:
    try:
        prices=[Decimal(v) for v in (input_price,output_price,ceiling)]
        if any(not v.is_finite() or v<0 or v>1000000 or v.as_tuple().exponent < -8 for v in prices) or prices[2]<=0 \
            or currency not in {"USD","KRW"} or not url or not model or len(model)>100 \
            or token_parameter not in {"max_tokens","max_completion_tokens"}:
            raise ValueError()
    except (ValueError,InvalidOperation,TypeError):raise AdvisoryFailure("AI_CONFIGURATION")
    return {"schemaVersion":"ai-execution-plan-v1","model":model,"providerFingerprint":sha256(url.encode()).hexdigest(),
            "inputPricePerMillion":float(prices[0]),"outputPricePerMillion":float(prices[1]),"currency":currency,
            "costCeiling":float(prices[2]),"embedding":None,"tokenParameter":token_parameter,"promptVersion":PROMPT_VERSION}

class ProcessProposal:
    def __init__(self,core:ProposalCore,model:StructuredModel,plan:dict,recognizer:RecognizeInvoice|None=None,embedding=None):
        self.core,self.model,self.plan,self.recognizer,self.embedding=core,model,plan,recognizer,embedding
    @staticmethod
    def durable(reply):
        keys(reply,{"disposition","runStatus"})
        if reply["disposition"]!="CHECKPOINTED" or reply["runStatus"] not in {"QUEUED","RUNNING","FAILED","STALE","COMPLETED"}:
            raise WorkerFailure("INVALID_PROTOCOL")
    def process(self,request:ProposalRequest,stopping):
        claim=self.core.claim(request)
        keys(claim,{"disposition","token","leaseUntil","context","steps"})
        disposition=claim["disposition"]
        if disposition in {"STALE","ALREADY_FINISHED"}:return
        if disposition=="BUSY":self.durable(self.core.defer(request));return
        if disposition!="CLAIMED":raise WorkerFailure("INVALID_PROTOCOL")
        token=uuid(claim["token"])
        try:self._execute(request,token,claim,stopping)
        except AdvisoryFailure as exc:self.durable(self.core.failure(request,token,exc.code))
        except WorkerFailure as exc:
            if exc.code in {"STALE_INPUT","LEASE_CONFLICT"}:self.durable(self.core.defer(request))
            elif exc.code in {"CORE_UNAVAILABLE","CORE_TRANSIENT","INVALID_PROTOCOL","SOURCE_MISMATCH"}:
                self.durable(self.core.failure(request,token,exc.code))
            else:raise
    def _execute(self,request,token,claim,stopping):
        require_lease(claim["leaseUntil"])
        context=claim["context"]
        if not isinstance(context,dict) or not isinstance(claim["steps"],list):raise WorkerFailure("INVALID_PROTOCOL")
        steps={}
        for s in claim["steps"]:
            keys(s,{"stage","hash","payload"});digest(s["hash"])
            if not isinstance(s["stage"],str) or not isinstance(s["payload"],dict):raise WorkerFailure("INVALID_PROTOCOL")
            steps[s["stage"]]=s["payload"]
        if len(steps)!=len(claim["steps"]):raise WorkerFailure("INVALID_PROTOCOL")
        deadline=time.monotonic()+300
        def active():
            if stopping():raise WorkerFailure("SHUTTING_DOWN")
            if time.monotonic()>=deadline:raise AdvisoryFailure("AI_TIMEOUT")
            lease=keys(self.core.heartbeat(request,token),{"leaseUntil"});require_lease(lease["leaseUntil"])
        def checkpoint(stage,payload):
            active();reply=keys(self.core.checkpoint(request,token,stage,payload),{"disposition"})
            if reply["disposition"]=="STALE":raise WorkerFailure("STALE_INPUT")
            if reply["disposition"] not in {"ACCEPTED","REPLAYED"}:raise WorkerFailure("INVALID_PROTOCOL")
            steps[stage]=payload;return payload
        if "execution" in steps and steps["execution"]!=self.plan:raise AdvisoryFailure("AI_CONFIGURATION")
        if "execution" not in steps:checkpoint("execution",self.plan)
        def before_call(tokens):
            active()
            reply=keys(self.core.reserve(request,token,str(uuid4()),tokens),{"reserved"})
            if reply["reserved"] is not True:raise WorkerFailure("INVALID_PROTOCOL")
        for document in context["documents"]:
            parsed=document["parsed"];stage="ocr:"+document["documentId"]
            if parsed["kind"]!="pdf" or any(p["text"].strip() for p in parsed["pdf"]["pages"]) or stage in steps:continue
            if self.recognizer is None:raise AdvisoryFailure("OCR_CONFIGURATION")
            metadata=next((d for d in context["evidenceBundle"]["documents"] if d["documentId"]==document["documentId"]),None)
            frozen=Document.decode(metadata)
            if frozen.size>4000000 or parsed["pdf"]["pageCount"]>2:raise AdvisoryFailure("OCR_LIMIT")
            active();data=self.core.source(request,token,frozen)
            before_call(1)  # F0 OCR is a logical call, not an LLM token usage claim.
            checkpoint(stage,self.recognizer.execute(frozen.document_id,data,frozen.checksum,parsed["pdf"]["pageCount"]))
        if "document" not in steps:
            ocr={k[4:]:v for k,v in steps.items() if k.startswith("ocr:")}
            checkpoint("document",DocumentAgent(self.model,before_call).execute(parser_segments(context,ocr)))
        if "mapping" not in steps:
            active();prior=self.core.tool(request,token,{"requestId":str(uuid4()),"tool":"get_prior_invoice_cases","query":"","limit":10})
            if prior.get("schemaVersion")!="ai-tool-v1":raise WorkerFailure("INVALID_PROTOCOL")
            checkpoint("mapping",ItemMappingAgent(self.model,before_call).execute(steps["document"],context["items"],prior["result"]))
        if not context["matchResult"]["normal"] and self.embedding is not None and context.get("policyDocuments") and "embedding" not in steps:
            config=self.plan["embedding"]
            if any(d["embeddingModel"]!=config["model"] or d["embeddingVersion"]!=config["version"] or d["embeddingDimension"]!=config["dimension"] for d in context["policyDocuments"]):raise AdvisoryFailure("AI_CONFIGURATION")
            query=policy_query(context);before_call(len(query.encode())+1)
            result=self.embedding.embed(query)
            checkpoint("embedding",{"schemaVersion":"ai-query-embedding-v1","query":query,"model":config["model"],"version":config["version"],"dimension":config["dimension"],**result})
        if "evidence" not in steps:
            def retrieve(query):
                active();embedded=steps.get("embedding")
                response=self.core.policy(request,token,{"requestId":str(uuid4()),"query":query,"mode":"HYBRID" if embedded else "LEXICAL",
                    "embeddingModel":embedded["model"] if embedded else None,"embeddingVersion":embedded["version"] if embedded else None,
                    "embedding":embedded["embedding"] if embedded else None,"limit":5})
                steps["tool:"+response["request"]["requestId"]]=response
                return response
            checkpoint("evidence",EvidenceAgent(retrieve).execute(context))
        stage=steps["evidence"]["result"]
        if stage["status"]=="NOT_REQUIRED":proof={"status":"NOT_REQUIRED","result":[]}
        else:
            tool="tool:"+stage["toolRequestId"]
            if tool not in steps:raise WorkerFailure("INVALID_PROTOCOL")
            proof=steps[tool]
        if "resolution" not in steps:checkpoint("resolution",ResolutionAgent(self.model,before_call).execute(context,steps["document"],steps["mapping"],proof))
        active();reply=keys(self.core.complete(request,token),{"disposition","proposalId","payloadHash"})
        if reply["disposition"]!="COMPLETED" or uuid(reply["proposalId"])!=request.run_id:raise WorkerFailure("INVALID_PROTOCOL")
        digest(reply["payloadHash"])
