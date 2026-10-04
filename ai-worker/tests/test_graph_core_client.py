import asyncio
from types import SimpleNamespace
import pytest
from ai_worker.application.execution import WorkerFailure
from ai_worker.infrastructure.graph_core_client import GraphCoreClient
from ai_worker.infrastructure.core_client import CoreClient


def client():return GraphCoreClient("http://127.0.0.1:1","x"*40)


def test_graph_bounded_array_routes_keep_strict_json_and_legacy_object_contract():
    graph=client()
    assert graph._decode_response(b'[{"ref":"x"}]',1024)==[{"ref":"x"}]
    for raw in [b'[{"ref":"x","ref":"y"}]',b'[NaN]',b'['+b'0,'*512+b'0]']:
        with pytest.raises(WorkerFailure,match="INVALID_PROTOCOL"):graph._decode_response(raw,4096)
    with pytest.raises(WorkerFailure,match="INVALID_PROTOCOL"):CoreClient("http://127.0.0.1:1","x"*40)._decode_response(b'[]',1024)


def test_waiting_refusal_cannot_use_legacy_quarantine_ack_code():
    class Content:
        async def iter_chunked(self,size):yield b'{"code":"GRAPH_WAIT_CONFLICT","message":"redacted"}'
    with pytest.raises(WorkerFailure,match="INVALID_PROTOCOL"):
        asyncio.run(client()._error_response(SimpleNamespace(content=Content())))
