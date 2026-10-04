import copy
import pytest
from ai_worker.application.resolution import core_facts, EvidenceAgent, ResolutionAgent, validate_resolution
from ai_worker.domain.advisory import AdvisoryFailure, ModelReply

CONTEXT={"matchResult":{"normal":False,"exceptions":[{"code":"QUANTITY_EXCEEDS_CONFIRMED"}],"lineOutcomes":[
    {"lineNumber":1,"invoiceQuantity":60,"invoiceUnitPrice":2500,"availableConfirmedQuantity":50,"plannedQuantity":50}]}}
DOC={"result":{"fields":[{"name":"supplierName"}],"lines":[],"warnings":[]}}
MAPPING={"result":{"lines":[]}}
CHUNK={"chunkId":"chunk","documentId":"policy","documentVersion":2,"page":3,"paragraph":4,"text":"😀 분할 청구는 추가 검수 자료를 확인한다."}
EVIDENCE={"status":"FOUND","result":[CHUNK]}

def value():
    return {"recommendation":"SUPPLEMENT_REQUEST","summary":"추가 검수 자료 확인을 요청하는 초안입니다.","factIds":["invoiceTotal"],
            "citations":[{"chunkId":"chunk","documentId":"policy","documentVersion":2,"page":3,"paragraph":4,
                          "start":2,"end":7,"quote":"분할 청구"}],"warnings":[]}

def test_core_arithmetic_uses_frozen_matching_not_model_values():
    assert core_facts(CONTEXT)["invoiceTotal"]=={"value":150000,"unit":"KRW"}
    assert core_facts(CONTEXT)["line:1:availableConfirmedQuantity"]["value"]==50
    assert validate_resolution(value(),CONTEXT,DOC,MAPPING,EVIDENCE)==value()
    bad=copy.deepcopy(CONTEXT);bad["matchResult"]["lineOutcomes"][0]["invoiceQuantity"]=2**63
    with pytest.raises(AdvisoryFailure):core_facts(bad)

@pytest.mark.parametrize("change",[
    lambda v:v.update(approve=True),lambda v:v.update(recommendation="APPROVAL_REVIEW"),
    lambda v:v.update(summary="금액은 150001원"),lambda v:v.update(summary="금액 Ⅷ"),
    lambda v:v.update(factIds=["unverifiedMoney"]),lambda v:v.update(citations=[]),
    lambda v:v["citations"][0].update(documentVersion=1),lambda v:v["citations"][0].update(page=2),
    lambda v:v["citations"][0].update(chunkId="foreign"),lambda v:v["citations"][0].update(documentId="foreign"),
    lambda v:v["citations"][0].update(quote="approve now"),lambda v:v["citations"][0].update(start=1),
    lambda v:v.update(warnings=["POLICY_CONFLICT"]),lambda v:v.update(recommendation="INSUFFICIENT_EVIDENCE")])
def test_forged_versions_locations_money_and_actions_fail(change):
    v=value();change(v)
    with pytest.raises(AdvisoryFailure,match="AI_SCHEMA_INVALID"):validate_resolution(v,CONTEXT,DOC,MAPPING,EVIDENCE)

@pytest.mark.parametrize("status",["CONFLICT","INSUFFICIENT_EVIDENCE"])
def test_missing_or_conflicting_evidence_stops_without_spending_model_budget(status):
    class Model:
        def generate(self,*args):pytest.fail("guard must not call provider")
    def budget(_):pytest.fail("guard must not spend budget")
    result=ResolutionAgent(Model(),budget).execute(CONTEXT,DOC,MAPPING,{"status":status,"result":[]})
    assert result["calls"]==[]
    assert result["result"]["recommendation"] in {"REVIEW_REQUIRED","INSUFFICIENT_EVIDENCE"}
    validate_resolution(result["result"],CONTEXT,DOC,MAPPING,{"status":status,"result":[]})

def test_alias_still_requires_human_review_and_normal_match_skips_policy_search():
    class Model:
        def generate(self,*args):pytest.fail("alias requires review")
    mapping={"result":{"lines":[{"reviewRequired":False,"candidates":[{"reasonCodes":["ALIAS"]}]}]}}
    assert "MAPPING_REVIEW" in ResolutionAgent(Model(),lambda _:None).execute(CONTEXT,DOC,mapping,EVIDENCE)["result"]["warnings"]
    normal=copy.deepcopy(CONTEXT);normal["matchResult"]["normal"]=True
    assert EvidenceAgent(lambda _:pytest.fail("normal must not retrieve")).execute(normal)["result"]["status"]=="NOT_REQUIRED"

def test_exception_search_scope_is_not_selected_by_source_instructions_and_model_is_bounded():
    queries=[]
    def retrieve(query):
        queries.append(query);return {"schemaVersion":"ai-evidence-v1","status":"FOUND","request":{"requestId":"request"}}
    assert EvidenceAgent(retrieve).execute(CONTEXT)["result"]["toolRequestId"]=="request"
    assert queries==["분할"]
    class Model:
        def generate(self,name,system,payload,schema,max_tokens):
            assert "untrusted" in system and "never a business decision" in system
            assert payload["facts"]["invoiceTotal"]["value"]==150000 and max_tokens==2000
            assert schema["additionalProperties"] is False
            return ModelReply(value(),"fixture",100,100,1)
    spent=[];output=ResolutionAgent(Model(),spent.append).execute(CONTEXT,DOC,MAPPING,EVIDENCE)
    assert len(spent)==len(output["calls"])==1
