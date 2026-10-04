import copy
import pytest
from test_structured_model import model_server
from ai_worker.infrastructure.embedding_model import EmbeddingModel
from ai_worker.domain.advisory import AdvisoryFailure

def response():return {"model":"fixture","data":[{"index":0,"embedding":[1,0]}],"usage":{"prompt_tokens":2,"total_tokens":2}}
def adapter(url):return EmbeddingModel(url,"fixture-key","fixture","1",2,allow_loopback_test_endpoint=True)

def test_embedding_wire_and_usage_are_bounded_and_explicit(model_server):
    url,state=model_server;state["reply"]=response();result=adapter(url).embed("분할")
    assert result["embedding"]==[1,0] and result["calls"][0]["inputTokens"]==2 and result["calls"][0]["outputTokens"]==0
    assert state["body"]=={"model":"fixture","input":"분할","encoding_format":"float","dimensions":2}
    assert state["hits"]==1

@pytest.mark.parametrize("change",[
    lambda n:n.update(model="foreign"),lambda n:n.update(data=[]),
    lambda n:n["data"][0].update(index=True),lambda n:n["data"][0].update(embedding=[0,0]),
    lambda n:n["data"][0].update(embedding=[1]),lambda n:n["data"][0].update(embedding=[True,0]),
    lambda n:n["data"][0].update(embedding=[float("nan"),0]),lambda n:n["usage"].update(prompt_tokens=1001,total_tokens=1001),
    lambda n:n["usage"].update(total_tokens=3),lambda n:n["data"][0].update(embedding=[1000001,0])])
def test_foreign_model_zero_nonfinite_wrong_dimension_and_unbounded_usage_fail(model_server,change):
    url,state=model_server;state["reply"]=response();change(state["reply"])
    with pytest.raises(AdvisoryFailure,match="AI_SCHEMA_INVALID"):adapter(url).embed("분할")

@pytest.mark.parametrize("status,code",[(302,"AI_FAILED"),(429,"AI_RATE_LIMIT"),(401,"AI_CONFIGURATION"),(500,"AI_FAILED")])
def test_embedding_errors_do_not_retry_or_redirect(model_server,status,code):
    url,state=model_server;state["reply"]=response();state["status"]=status
    with pytest.raises(AdvisoryFailure,match=code):adapter(url).embed("분할")
    assert state["hits"]==1
