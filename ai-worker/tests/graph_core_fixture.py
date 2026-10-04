"""Linux installed-wheel fixture talks to the real authenticated Core; no production provider calls."""
import json
import os
from pathlib import Path
import sys

from ai_worker.application.execution import WorkerFailure
from ai_worker.application.graph_execution import GraphRequest, GraphSession
from ai_worker.application.proposal_execution import execution_plan
from ai_worker.composition import build_graph_processor
from ai_worker.domain.advisory import ModelReply
from ai_worker.infrastructure.graph_core_client import GraphCoreClient
from ai_worker.infrastructure.graph_runtime import LangGraphRuntime


def run():
    assert "site-packages" in __import__("ai_worker").__file__
    mode,base,run_id,context_hash=sys.argv[1:]
    request=GraphRequest(run_id,context_hash)
    ledger=Path("/tmp/graph-"+run_id+".json")
    data=json.loads(ledger.read_text()) if ledger.exists() else {"calls":0}
    def save():ledger.write_text(json.dumps(data))
    plan=execution_plan("https://fixture.invalid/chat","fixture","1","2","USD","1","max_completion_tokens")
    class Core(GraphCoreClient):
        def claim(self,r):
            claim=super().claim(r)
            if claim["disposition"]=="CLAIMED":data["claim"]=claim;save()
            return claim
        def waiting(self,r,t,proof):
            if mode=="break-wait":raise WorkerFailure("CORE_UNAVAILABLE")
            return super().waiting(r,t,proof)
    core=Core(base,os.environ["GRAPH_FIXTURE_WORKER_TOKEN"])
    class Model:
        def generate(self,name,*_):
            data["calls"]+=1;save()
            if name=="invoice_extraction":
                if mode=="normal":
                    context=data["claim"]["context"];document=context["documents"][0]
                    payload={"fields":[{"name":"supplierName","value":"Premium Copy Paper A4","source":{"segmentId":document["documentId"]+":page:1","start":0,"end":21}}],"lines":[],"warnings":[]}
                else:payload={"fields":[],"lines":[],"warnings":["EMPTY_DOCUMENT"]}
            else:
                assert name=="review_resolution"
                payload={"recommendation":"APPROVAL_REVIEW","summary":"대사와 검토 근거를 확인했습니다.","factIds":["invoiceTotal"],"citations":[],"warnings":[]}
            return ModelReply(payload,"fixture",100,100,1)
    if mode=="restore":
        claim=data["claim"]
        session=GraphSession(core,request,claim["token"],claim["context"],Model(),plan,lambda:False)
        result=LangGraphRuntime().execute(session)
        assert result["disposition"]=="WAITING_HUMAN" and data["calls"]==1
    else:
        try:build_graph_processor(core,Model(),plan).process(request,lambda:False)
        except WorkerFailure as exc:
            if mode!="break-wait" or exc.code!="CORE_UNAVAILABLE":raise
    print(json.dumps({"modelCalls":data["calls"],"mode":mode}))


if __name__=="__main__":run()
