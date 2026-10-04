import copy
import json
from datetime import datetime,timedelta,timezone
from uuid import uuid4
import pytest
from ai_worker.application.proposal_execution import ProposalRequest,ProcessProposal,execution_plan
from ai_worker.application.execution import WorkerFailure
from ai_worker.domain.advisory import AdvisoryFailure,ModelReply

PLAN=execution_plan("https://model.invalid/chat","fixture","1","2","USD","0.1","max_completion_tokens")

def message():
    run=str(uuid4());return {"schemaVersion":"ai-request-v1","eventId":run,"proposalRunId":run,"contextHash":"a"*64,"workflowVersion":"ai-review-v1"}
def decode(n):return ProposalRequest.decode(json.dumps(n).encode(),n["eventId"],"application/json","InvoiceProposalRequested","UTF-8")
def lease():return (datetime.now(timezone.utc)+timedelta(seconds=120)).isoformat()

class Core:
    def __init__(self):
        self.id=str(uuid4());self.steps={};self.reservations=[];self.failures=[];self.completed=0;self.deferred=0;self.stale=False;self.lost=None
    def claim(self,_):
        return {"disposition":"CLAIMED","token":str(uuid4()),"leaseUntil":lease(),"context":{
            "documents":[{"documentId":self.id,"checksum":"b"*64,"parsed":{"kind":"pdf","pdf":{"pageCount":1,"pages":[{"page":1,"text":"INV-1"}]}}}],
            "evidenceBundle":{"documents":[]},"items":[],"matchResult":{"normal":True,"exceptions":[],"lineOutcomes":[]}},
            "steps":[{"stage":k,"hash":"c"*64,"payload":v} for k,v in self.steps.items()]}
    def heartbeat(self,*_):
        if self.stale:raise WorkerFailure("STALE_INPUT")
        return {"leaseUntil":lease()}
    def checkpoint(self,r,t,stage,payload):
        self.steps[stage]=copy.deepcopy(payload)
        if self.lost==stage:self.lost=None;raise WorkerFailure("CORE_UNAVAILABLE")
        return {"disposition":"ACCEPTED"}
    def reserve(self,r,t,request,tokens):
        if len(self.reservations)==5:raise AdvisoryFailure("AI_BUDGET_EXHAUSTED")
        self.reservations.append((request,tokens));return {"reserved":True}
    def tool(self,*_):return {"schemaVersion":"ai-tool-v1","result":[]}
    def policy(self,*_):pytest.fail("normal match must not search")
    def source(self,*_):pytest.fail("text layer must not OCR")
    def complete(self,r,t):
        self.completed+=1
        if self.lost=="complete":self.lost=None;raise WorkerFailure("CORE_UNAVAILABLE")
        return {"disposition":"COMPLETED","proposalId":r.run_id,"payloadHash":"d"*64}
    def failure(self,r,t,code):self.failures.append(code);return {"disposition":"CHECKPOINTED","runStatus":"QUEUED"}
    def defer(self,*_):self.deferred+=1;return {"disposition":"CHECKPOINTED","runStatus":"STALE" if self.stale else "RUNNING"}

class Model:
    def __init__(self,core):self.core=core;self.calls=0;self.error=None
    def generate(self,name,*args):
        self.calls+=1
        if self.error:raise AdvisoryFailure(self.error)
        assert name=="invoice_extraction"
        return ModelReply({"fields":[],"lines":[],"warnings":["EMPTY_DOCUMENT"]},"fixture",100,100,1)

def test_fixed_ai_message_cannot_enter_the_parser_workflow_or_select_another_run():
    n=message();assert decode(n).run_id==n["proposalRunId"]
    for k,v in [("workflowVersion","document-parser-v1"),("eventId",str(uuid4())),("contextHash","forged"),("extra","approve")]:
        bad={**n,k:v}
        with pytest.raises(WorkerFailure):decode(bad)
    with pytest.raises(WorkerFailure):ProposalRequest.decode(json.dumps(n).encode(),n["eventId"],"application/json","InvoiceAnalysisRequested","UTF-8")

@pytest.mark.parametrize("lost",["document","mapping","evidence","resolution","complete"])
def test_lost_checkpoint_or_completion_reuses_saved_stages_without_repeating_provider(lost):
    core=Core();model=Model(core);core.lost=lost;processor=ProcessProposal(core,model,PLAN);request=decode(message())
    processor.process(request,lambda:False)
    assert core.failures==["CORE_UNAVAILABLE"] and model.calls==len(core.reservations)==1
    processor.process(request,lambda:False)
    assert model.calls==len(core.reservations)==1
    assert set(core.steps)=={"execution","document","mapping","evidence","resolution"}
    assert core.completed>=1
    assert core.steps["resolution"]["result"]["recommendation"]=="REVIEW_REQUIRED"

@pytest.mark.parametrize("code",["AI_RATE_LIMIT","AI_TIMEOUT","AI_BUDGET_EXHAUSTED"])
def test_external_failure_is_acknowledgeable_only_after_durable_checkpoint(code):
    core=Core();model=Model(core);model.error=code;processor=ProcessProposal(core,model,PLAN)
    processor.process(decode(message()),lambda:False)
    assert core.failures==[code] and len(core.reservations)==1 and core.completed==0
    def unavailable(*_):raise WorkerFailure("CORE_UNAVAILABLE")
    core.failure=unavailable
    with pytest.raises(WorkerFailure):processor.process(decode(message()),lambda:False)
    assert len(core.reservations)==2

def test_configuration_change_on_reclaim_cannot_reset_uncertain_call_budget():
    core=Core();model=Model(core);core.steps["execution"]={**PLAN,"model":"old-model"}
    ProcessProposal(core,model,PLAN).process(decode(message()),lambda:False)
    assert core.failures==["AI_CONFIGURATION"] and model.calls==len(core.reservations)==0

def test_stale_and_shutdown_fence_external_calls_and_delivery():
    core=Core();model=Model(core);core.stale=True
    ProcessProposal(core,model,PLAN).process(decode(message()),lambda:False)
    assert core.deferred==1 and model.calls==0
    core.stale=False
    with pytest.raises(WorkerFailure,match="SHUTTING_DOWN"):ProcessProposal(core,model,PLAN).process(decode(message()),lambda:True)
    assert model.calls==0

@pytest.mark.parametrize("field,value",[("ceiling","0"),("ceiling","NaN"),("input_price","-1"),("currency","EUR"),("output_price","0.000000001")])
def test_execution_cost_settings_are_explicit_finite_and_bounded(field,value):
    args={"url":"https://model.invalid","model":"fixture","input_price":"1","output_price":"2","currency":"USD","ceiling":"0.1","token_parameter":"max_tokens"};args[field]=value
    with pytest.raises(AdvisoryFailure,match="AI_CONFIGURATION"):execution_plan(**args)
