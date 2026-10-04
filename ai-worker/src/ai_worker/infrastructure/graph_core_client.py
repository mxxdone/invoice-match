"""Graph machine HTTP boundary. Shares fixed-origin bounded transport, never a DB handle."""
import asyncio
from ai_worker.application.execution import WorkerFailure, strict_json
from ai_worker.infrastructure.proposal_core_client import ProposalCoreClient


class GraphCoreClient(ProposalCoreClient):
    def _decode_response(self, raw, cap):
        if raw.lstrip().startswith(b"["):
            if len(raw)>cap:raise WorkerFailure("INVALID_PROTOCOL")
            # Reuse strict duplicate-key/finite-number decoding for the two bounded array routes.
            result=strict_json(b'{"items":'+raw+b'}',cap+10)["items"]
            if not isinstance(result,list) or len(result)>512:raise WorkerFailure("INVALID_PROTOCOL")
            return result
        return strict_json(raw,cap)
    async def _error_response(self, response):
        data=bytearray()
        async for chunk in response.content.iter_chunked(2048):
            if len(data)+len(chunk)>8192:raise WorkerFailure("INVALID_PROTOCOL")
            data.extend(chunk)
        code=strict_json(bytes(data),8192).get("code")
        if code=="GRAPH_STORAGE_LIMIT":raise WorkerFailure("GRAPH_LIMIT")
        if code in {"GRAPH_CHECKPOINT_MISSING", "LEASE_CONFLICT", "STALE_INPUT"}:raise WorkerFailure(code)
        if code in {"AI_CONFIGURATION", "AI_SCHEMA_INVALID", "AI_BUDGET_EXHAUSTED", "AI_INPUT_LIMIT", "TOOL_DENIED", "AI_EMBEDDING_MISMATCH", "VECTOR_UNAVAILABLE"}:
            from ai_worker.domain.advisory import AdvisoryFailure
            raise AdvisoryFailure("AI_TOOL_DENIED" if code in {"TOOL_DENIED", "AI_EMBEDDING_MISMATCH", "VECTOR_UNAVAILABLE"} else code)
        # A graph persistence refusal is never the v1 quarantine/ACK path.
        raise WorkerFailure("INVALID_PROTOCOL")

    def _call(self,request,suffix,body,document=None):
        reply=asyncio.run(self._post("/internal/graph-runs/"+request.run_id+suffix,{"contextHash":request.context_hash,**body},document))
        if not document and isinstance(reply,list)!=(suffix in {"/writes","/stages/read"}):raise WorkerFailure("INVALID_PROTOCOL")
        return reply
    def stages(self,r,t):return self._call(r,"/stages/read",{"token":t})
    def claim(self,r):return self._call(r,"/resume/claim",{"event":r.event}) if r.event is not None else self._call(r,"/claim",{})
    def resume(self,r,t):return self._call(r,"/resume/read",{"token":t,"event":r.event})
    def defer(self,r):return self._call(r,"/defer",{"segment":"RESUME" if r.event is not None else "START","event":r.event})
    def stage(self,r,t,name,payload):return self._call(r,"/stages",{"token":t,"stage":name,"payload":payload})
    def checkpoint(self,r,t,checkpoint):return self._call(r,"/checkpoints",{"token":t,"checkpoint":checkpoint})
    def read(self,r,t,checkpoint_id=None):
        try:return self._call(r,"/checkpoints/read",{"token":t,"checkpointId":checkpoint_id})
        except WorkerFailure as exc:
            if exc.code=="GRAPH_CHECKPOINT_MISSING" and checkpoint_id is None:return None
            raise
    def writes(self,r,t,writes):return self._call(r,"/writes",{"token":t,"writes":writes})
    def waiting(self,r,t,proof):return self._call(r,"/waiting",{"token":t,"interrupt":proof})
