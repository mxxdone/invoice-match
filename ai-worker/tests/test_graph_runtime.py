"""Installed SDK, finite workflow and consumer ACK boundary with an isolated Core protocol fixture."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import os
from uuid import uuid4

import pytest

from ai_worker.application.execution import WorkerFailure
from ai_worker.application.graph_execution import GraphRequest, GraphSession, human_reasons
from ai_worker.composition import build_graph_processor
from ai_worker.domain.advisory import ModelReply
from test_proposal_execution import PLAN, lease


def hash_json(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(",", ":")).encode()).hexdigest()


class Core:
    """Protocol fixture only, never a production database/checkpointer substitute."""
    def __init__(self, path=None):
        self.path=Path(path) if path else None
        self.data=json.loads(self.path.read_text()) if self.path and self.path.exists() else {
            "id":str(uuid4()),"token":str(uuid4()),"status":"QUEUED","stages":{},"checkpoints":{},"calls":0,"reserved":0,"modelCalls":0,"waiting":None,"failures":[]}
        self.lost=None;self.wait_fail=False
    def persist(self):
        if self.path:self.path.write_text(json.dumps(self.data,ensure_ascii=False))
    def claim(self,r):
        self.data["token"]=str(uuid4());status=self.data["status"]
        if status=="WAITING_HUMAN":disposition=status
        elif status in {"COMPLETED","FAILED"}:disposition="ALREADY_FINISHED"
        else:disposition="CLAIMED";self.data["status"]="RUNNING"
        self.persist()
        return {"disposition":disposition,"token":self.data["token"] if disposition=="CLAIMED" else None,"leaseUntil":lease() if disposition=="CLAIMED" else None,
            "waiting":self.data["waiting"],"context":{"documents":[{"documentId":self.data["id"],"checksum":"b"*64,"parsed":{"kind":"pdf","pdf":{"pageCount":1,"pages":[{"page":1,"text":"INV-1"}]}}}],
                "evidenceBundle":{"documents":[]},"items":[],"policyDocuments":[],"matchResult":{"normal":True,"exceptions":[],"lineOutcomes":[]}} if disposition=="CLAIMED" else None}
    def heartbeat(self,*_):return {"leaseUntil":lease()}
    def stages(self,*_):return copy.deepcopy(list(self.data["stages"].values()))
    def stage(self,r,t,name,payload):
        if name not in self.data["stages"]:
            self.data["stages"][name]={"ref":str(uuid4()),"stage":name,"hash":hash_json(payload),"payload":copy.deepcopy(payload)};self.persist()
        if self.lost==name:self.lost=None;raise WorkerFailure("CORE_UNAVAILABLE")
        return copy.deepcopy(self.data["stages"][name])
    def reserve(self,r,t,id,tokens):self.data["reserved"]+=tokens;self.data["calls"]+=1;self.persist();return {"reserved":True}
    def tool(self,*_):return {"schemaVersion":"ai-tool-v1","result":[]}
    def policy(self,*_):pytest.fail("normal match does not retrieve policies")
    def source(self,*_):pytest.fail("text layer does not OCR")
    def checkpoint(self,r,t,checkpoint):
        cid=checkpoint["checkpointId"];h=hash_json(checkpoint);old=self.data["checkpoints"].get(cid)
        if old:
            assert old["hash"]==h;return {"disposition":"REPLAYED","hash":h}
        assert len(json.dumps(checkpoint).encode())<=262144
        self.data["checkpoints"][cid]={"id":cid,"parentId":checkpoint["parentId"],"hash":h,"envelope":copy.deepcopy(checkpoint),"writes":[]};self.persist()
        if self.lost=="sdk":self.lost=None;raise WorkerFailure("CORE_UNAVAILABLE")
        return {"disposition":"ACCEPTED","hash":h}
    def read(self,r,t,checkpoint_id=None):
        if not self.data["checkpoints"]:return None
        cid=checkpoint_id or next(reversed(self.data["checkpoints"]))
        return copy.deepcopy(self.data["checkpoints"][cid])
    def writes(self,r,t,commands):
        replies=[]
        for c in commands:
            cp=self.data["checkpoints"][c["checkpointId"]];old=next((w for w in cp["writes"] if (w["taskId"],w["index"])==(c["taskId"],c["index"])),None)
            if old:
                if c["version"]==old["version"]:
                    assert old["hash"]==hash_json(c);replies.append({"disposition":"REPLAYED","hash":old["hash"]});continue
                assert c["index"]<0 and c["version"]==old["version"]+1 and c["previousHash"]==old["hash"]
                cp["writes"].remove(old)
            cp["writes"].append({**copy.deepcopy(c),"hash":hash_json(c)})
            replies.append({"disposition":"ACCEPTED","hash":hash_json(c)})
        self.persist();return replies
    def waiting(self,r,t,proof):
        if self.wait_fail:raise WorkerFailure("CORE_UNAVAILABLE")
        self.data["waiting"]={**proof,"reviewVersion":1};self.data["status"]="WAITING_HUMAN";self.persist()
        if self.lost=="waiting":self.lost=None;raise WorkerFailure("CORE_UNAVAILABLE")
        return self.data["waiting"]
    def complete(self,r,t):self.data["status"]="COMPLETED";self.persist();return {"disposition":"COMPLETED","proposalId":r.run_id,"payloadHash":"d"*64}
    def failure(self,r,t,code):self.data["failures"].append(code);self.data["status"]="FAILED";self.persist();return {"disposition":"CHECKPOINTED","runStatus":"FAILED"}


class Model:
    def __init__(self,core):self.core=core
    def generate(self,name,*_):
        self.core.data["modelCalls"]+=1;self.core.persist()
        return ModelReply({"fields":[],"lines":[],"warnings":["EMPTY_DOCUMENT"]},"fixture",100,100,1)


def processor(core):return build_graph_processor(core,Model(core),PLAN)
def request(core):return GraphRequest(core.data["id"],"a"*64)


def test_actual_sdk_waits_and_duplicate_delivery_does_not_recall_model():
    core=Core();processor(core).process(request(core),lambda:False)
    assert core.data["status"]=="WAITING_HUMAN" and core.data["calls"]==core.data["modelCalls"]==1
    assert not core.data["failures"]
    processor(core).process(request(core),lambda:False)
    assert core.data["calls"]==core.data["modelCalls"]==1
    state=json.dumps(core.data["checkpoints"])
    assert "INV-1" not in state and "providerFingerprint" not in state and "documentStageRef" in state


@pytest.mark.parametrize("lost",["document","mapping","sdk","waiting"])
def test_saved_stage_or_sdk_response_loss_is_restored_in_new_process(tmp_path,lost):
    ledger=tmp_path/"core.json"
    core=Core(ledger);core.lost=lost
    with pytest.raises(WorkerFailure,match="CORE_UNAVAILABLE"):processor(core).process(request(core),lambda:False)
    before=core.data["modelCalls"]
    subprocess.run([sys.executable,__file__,str(ledger)],check=True,timeout=30,
        env={**os.environ,"PYTHONPATH":str(Path(__file__).parents[1]/"src")})
    restored=Core(ledger)
    assert restored.data["status"]=="WAITING_HUMAN"
    assert restored.data["modelCalls"]==max(1,before)
    assert restored.data["calls"]==1


def test_wait_failure_keeps_delivery_unacknowledged_and_next_case_is_not_held_by_waiting():
    from types import SimpleNamespace
    from test_rabbit_consumer import Loop
    from ai_worker.infrastructure.rabbit_consumer import RabbitConsumer
    core=Core();core.wait_fail=True
    node={"schemaVersion":"graph-request-v1","eventId":core.data["id"],"graphExecutionId":core.data["id"],"contextHash":"a"*64,"workflowVersion":"ai-review-v2"}
    consumer=RabbitConsumer(processor(core),{},GraphRequest.decode);loop=Loop();consumer.connection=SimpleNamespace(ioloop=loop)
    acknowledgements=[];errors=[];consumer._stop=lambda code:errors.append(code)
    channel=SimpleNamespace(is_open=True,basic_ack=lambda **kw:acknowledgements.append(kw["delivery_tag"]))
    props=SimpleNamespace(message_id=node["eventId"],content_type="application/json",type="InvoiceGraphRequested",content_encoding="UTF-8")
    consumer._delivery(channel,SimpleNamespace(delivery_tag=1),props,json.dumps(node).encode());consumer.job.join(10);assert not consumer.job.is_alive()
    loop.callbacks.pop(0)();assert acknowledgements==[] and errors==["CORE_UNAVAILABLE"]
    core.wait_fail=False;consumer._delivery(channel,SimpleNamespace(delivery_tag=2),props,json.dumps(node).encode());consumer.job.join(10);loop.callbacks.pop(0)()
    assert acknowledgements==[2] and core.data["status"]=="WAITING_HUMAN"
    other=Core();consumer.processor=processor(other);node.update(eventId=other.data["id"],graphExecutionId=other.data["id"]);props.message_id=other.data["id"]
    consumer._delivery(channel,SimpleNamespace(delivery_tag=3),props,json.dumps(node).encode());consumer.job.join(10);loop.callbacks.pop(0)()
    assert acknowledgements==[2,3] and other.data["status"]=="WAITING_HUMAN"


def test_reference_only_human_policy_limits_and_legacy_message_separation():
    assert human_reasons({"result":{"fields":[{}],"lines":[{}],"warnings":[]}}, {"result":{"lines":[{"candidates":[{}]}]}})==[]
    assert human_reasons({"result":{"fields":[],"lines":[],"warnings":[]}}, {"result":{"lines":[{"candidates":[]},{"candidates":[{},{}]}]}})==["AMBIGUOUS_ITEM","DOCUMENT_REVIEW_REQUIRED","NO_ITEM_CANDIDATE"]
    core=Core();r=request(core);claim=core.claim(r)
    session=GraphSession(core,r,claim["token"],claim["context"],Model(core),PLAN,lambda:False,wall_seconds=0)
    with pytest.raises(WorkerFailure,match="GRAPH_LIMIT"):session.execution()
    assert core.data["calls"]==0
    with pytest.raises(WorkerFailure,match="INVALID_MESSAGE"):
        GraphRequest.decode(json.dumps({"schemaVersion":"graph-request-v1","eventId":r.run_id,"graphExecutionId":r.run_id,"contextHash":r.context_hash,"workflowVersion":"ai-review-v1"}).encode(),r.run_id,"application/json","InvoiceGraphRequested","UTF-8")


if __name__=="__main__":
    restored=Core(sys.argv[1]);processor(restored).process(request(restored),lambda:False)
