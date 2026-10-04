"""Real pinned SDK resumes the exact immutable review and preserves successful stages."""
import json
import os
import signal
import subprocess
import sys
from dataclasses import replace
from uuid import uuid4
import pytest
from ai_worker.application.execution import WorkerFailure
from ai_worker.application.graph_execution import GraphRequest
from test_graph_runtime import Core, processor, request


class ResumeCore(Core):
    def resume(self,r,t):
        return {"reviewRef":r.event["reviewId"],"checkpointId":r.event["checkpointId"],"checkpointHash":r.event["checkpointHash"],
            "interruptId":r.event["interruptId"],"reviewVersion":1,"confirmation":{"documentDecision":"CONFIRMED"}}
    def failure(self,r,t,code):
        if code=="CORE_UNAVAILABLE":raise WorkerFailure(code)
        return super().failure(r,t,code)
    def writes(self,r,t,writes):
        replies=super().writes(r,t,writes)
        if self.lost=="resume" and any(w["channel"]=="__resume__" for w in writes):
            self.lost=None;raise WorkerFailure("CORE_UNAVAILABLE")
        return replies


def reviewed(core):
    processor(core).process(request(core),lambda:False)
    w=core.data["waiting"];core.data["status"]="QUEUED"
    event={"schemaVersion":"graph-resume-request-v1","eventId":str(uuid4()),"graphExecutionId":core.data["id"],"contextHash":"a"*64,
        "workflowVersion":"ai-review-v2","reviewId":str(uuid4()),"reviewVersion":1,"interruptId":w["interruptId"],
        "checkpointId":w["checkpointId"],"checkpointHash":w["checkpointHash"]}
    return replace(request(core),event=event)


@pytest.mark.parametrize("lost",[None,"resume","sdk"])
def test_resume_response_loss_does_not_repeat_document_or_budget(lost):
    core=ResumeCore();r=reviewed(core);calls=core.data["calls"];core.lost=lost
    if lost:
        with pytest.raises(WorkerFailure,match="CORE_UNAVAILABLE"):processor(core).process(r,lambda:False)
    processor(core).process(r,lambda:False)
    assert core.data["status"]=="COMPLETED" and core.data["calls"]==calls
    assert core.data["modelCalls"]==1 and core.data["failures"]==[]
    from ai_worker.infrastructure.graph_serializer import GraphCheckpointSerializer
    codec=GraphCheckpointSerializer();cp=core.read(r,None)
    assert codec.loads_typed((codec.TYPE,json.dumps(cp["envelope"]["body"]).encode()))["channel_values"]["reviewRef"]==r.event["reviewId"]
    processor(core).process(r,lambda:False)
    assert core.data["modelCalls"]==1


def test_resume_message_is_closed_and_start_legacy_decoders_reject_it():
    from ai_worker.application.execution import Request
    from ai_worker.application.proposal_execution import ProposalRequest
    core=ResumeCore();r=reviewed(core);wire=json.dumps(r.event).encode()
    args=(wire,r.event["eventId"],"application/json","InvoiceGraphResumeRequested","UTF-8")
    assert GraphRequest.decode_resume(*args)==r
    for decoder in (GraphRequest.decode,Request.decode,ProposalRequest.decode):
        with pytest.raises(WorkerFailure):decoder(*args)
    for change in ({"confirmation":{}},{"reviewVersion":True},{"workflowVersion":"ai-review-v1"},{"checkpointHash":"bad"}):
        with pytest.raises(WorkerFailure):GraphRequest.decode_resume(json.dumps(r.event|change).encode(),*args[1:])


def test_busy_ack_requires_durable_defer_and_recovery_response_loss_keeps_delivery():
    from test_graph_runtime import lease
    core=ResumeCore();r=request(core)
    core.claim=lambda r:{"disposition":"BUSY","token":None,"leaseUntil":lease(),"context":None,"waiting":None}
    core.defer=lambda r:{"disposition":"CHECKPOINTED","runStatus":"RUNNING"}
    processor(core).process(r,lambda:False)
    core.defer=lambda r:{"disposition":"LOST","runStatus":"RUNNING"}
    with pytest.raises(WorkerFailure,match="INVALID_PROTOCOL"):processor(core).process(r,lambda:False)


def test_resume_cannot_change_frozen_provider_plan_or_reset_budget():
    from ai_worker.composition import build_graph_processor
    from test_graph_runtime import Model
    from test_proposal_execution import PLAN
    core=ResumeCore();r=reviewed(core);calls=core.data["calls"]
    build_graph_processor(core,Model(core),PLAN|{"model":"changed-provider-model"}).process(r,lambda:False)
    assert core.data["status"]=="FAILED" and core.data["failures"]==["AI_CONFIGURATION"]
    assert core.data["calls"]==calls and core.data["modelCalls"]==1


@pytest.mark.skipif(not sys.platform.startswith("linux"),reason="actual SIGKILL requires Linux")
def test_sigkill_after_resume_write_reclaims_exact_command_in_a_new_process(tmp_path):
    ledger=tmp_path/"core.json";core=ResumeCore(ledger);r=reviewed(core);core.data["event"]=r.event;core.persist()
    child=subprocess.run([sys.executable,__file__,str(ledger)],timeout=30)
    assert child.returncode==-signal.SIGKILL
    restored=ResumeCore(ledger);processor(restored).process(r,lambda:False)
    assert restored.data["status"]=="COMPLETED" and restored.data["calls"]==restored.data["modelCalls"]==1
    assert restored.data["failures"]==[]
    from ai_worker.infrastructure.graph_serializer import GraphCheckpointSerializer
    codec=GraphCheckpointSerializer();cp=restored.read(r,None)
    assert codec.loads_typed((codec.TYPE,json.dumps(cp["envelope"]["body"]).encode()))["channel_values"]["reviewRef"]==r.event["reviewId"]


if __name__=="__main__":
    core=ResumeCore(sys.argv[1]);r=GraphRequest(core.data["id"],"a"*64,core.data["event"])
    original=core.writes
    def killed(*args):
        result=original(*args)
        if any(w["channel"]=="__resume__" for w in args[2]):os.kill(os.getpid(),signal.SIGKILL)
        return result
    core.writes=killed;processor(core).process(r,lambda:False)
