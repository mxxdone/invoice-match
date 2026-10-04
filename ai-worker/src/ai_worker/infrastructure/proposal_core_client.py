"""AI protocol adapter shares only the bounded fixed-origin HTTP transport."""
import asyncio
from ai_worker.application.execution import WorkerFailure, strict_json
from ai_worker.domain.advisory import AdvisoryFailure, ERROR_CODES
from ai_worker.infrastructure.core_client import CoreClient

class ProposalCoreClient(CoreClient):
    json_cap = 2 * 1024 * 1024
    async def _error_response(self,response):
        data=bytearray()
        async for chunk in response.content.iter_chunked(2048):
            if len(data)+len(chunk)>8192:raise WorkerFailure("INVALID_PROTOCOL")
            data.extend(chunk)
        error=strict_json(bytes(data),8192).get("code")
        if error in {"STALE_INPUT","LEASE_CONFLICT"}:raise WorkerFailure(error)
        if error in ERROR_CODES:raise AdvisoryFailure(error)
        if error in {"TOOL_DENIED","AI_EMBEDDING_MISMATCH","VECTOR_UNAVAILABLE"}:raise AdvisoryFailure("AI_TOOL_DENIED")
        if error in {"VALIDATION_ERROR","MALFORMED_REQUEST"}:raise WorkerFailure("INVALID_PROTOCOL")
        raise WorkerFailure("CORE_REQUEST_FAILED")
    def _call(self,request,suffix,body,document=None):
        return asyncio.run(self._post("/internal/proposal-runs/"+request.run_id+suffix,{"contextHash":request.context_hash,**body},document))
    def claim(self,request):return self._call(request,"/claim",{})
    def defer(self,request):return self._call(request,"/defer",{})
    def heartbeat(self,request,token):return self._call(request,"/heartbeat",{"token":token})
    def checkpoint(self,request,token,stage,payload):return self._call(request,"/checkpoints",{"token":token,"stage":stage,"payload":payload})
    def reserve(self,request,token,request_id,tokens):return self._call(request,"/calls",{"token":token,"requestId":request_id,"tokens":tokens})
    def tool(self,request,token,args):return self._call(request,"/tools",{"token":token,"request":args})
    def policy(self,request,token,args):return self._call(request,"/policies",{"token":token,"request":args})
    def source(self,request,token,document):return self._call(request,"/documents/"+document.document_id+"/source",{"token":token},document)
    def complete(self,request,token):return self._call(request,"/complete",{"token":token})
    def failure(self,request,token,code):return self._call(request,"/failures",{"token":token,"errorCode":code})
