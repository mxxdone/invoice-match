"""P4-06 harness test-only model fixture.

It is adapted from ai-worker/tests/graph_core_fixture.py and still drives the
real installed ai_worker wheel, the real LangGraph runtime and the real
authenticated Core. Only the deterministic model replies change so a pending
human review can carry a source-backed document line plus mapping candidates of
length 0 and 2. It is verification-only code; it never runs in a product image.
"""
import json
import os
from pathlib import Path
import sys

from ai_worker.application.execution import WorkerFailure
from ai_worker.application.graph_execution import GraphRequest, GraphSession
from ai_worker.application.proposal_execution import execution_plan
from ai_worker.composition import build_graph_processor
from ai_worker.domain.advisory import ModelReply, AdvisoryFailure
from ai_worker.infrastructure.graph_core_client import GraphCoreClient
from ai_worker.infrastructure.graph_runtime import LangGraphRuntime

SAMPLE = {
    1: {"raw": "Premium Copy Paper A4", "quantity": "7", "price": "2500"},
    2: {"raw": "Laser Toner Black", "quantity": "9", "price": "55000"},
}


def _value(source, text, substring):
    start = text.index(substring)
    return {"value": substring, "source": {"segmentId": source["segment_id"], "start": start, "end": start + len(substring)}}


class RichModel:
    """Deterministic replies: source-backed document line(s) and 0/2 mapping candidates."""

    def __init__(self, ledger, save):
        self.ledger, self.save = ledger, save

    def generate(self, name, system, payload, schema, max_tokens):
        self.ledger["calls"] += 1
        self.save()
        if name == "invoice_extraction":
            return self._document(payload)
        if name == "item_mapping":
            return self._mapping(payload)
        assert name == "review_resolution"
        return ModelReply({"recommendation": "APPROVAL_REVIEW", "summary": "대사와 검토 근거를 확인했습니다.",
                           "factIds": ["invoiceTotal"], "citations": [], "warnings": []}, "fixture", 100, 100, 1)

    def _document(self, payload):
        sources = payload["sources"]
        fields = []
        lines = []
        for number, spec in SAMPLE.items():
            source = sources[number - 1]
            text = source["text"]
            if number == 1:
                fields.append({"name": "supplierName", "value": spec["raw"], "source": _value(source, text, spec["raw"])["source"]})
            lines.append({
                "lineNumber": number,
                "rawItemName": _value(source, text, spec["raw"]),
                "quantity": _value(source, text, spec["quantity"]),
                "unitPrice": _value(source, text, spec["price"]),
            })
        return ModelReply({"fields": fields, "lines": lines, "warnings": []}, "fixture", 100, 100, 1)

    def _mapping(self, payload):
        lines = payload["lines"]
        items = payload["items"]
        candidates = [{"itemId": item["itemId"], "purchaseOrderLineId": item["purchaseOrderLineId"],
                       "reasonCodes": ["ALIAS"], "reason": "표현이 달라 후보로 제안합니다.", "priorSnapshotId": None}
                      for item in items[:2]]
        result = []
        for line in lines:
            if line["lineNumber"] == 1 and len(candidates) == 2:
                result.append({"lineNumber": line["lineNumber"], "source": line["rawItemName"]["source"],
                               "candidates": candidates, "reviewRequired": True, "warningCodes": ["AMBIGUOUS"]})
            else:
                result.append({"lineNumber": line["lineNumber"], "source": line["rawItemName"]["source"],
                               "candidates": [], "reviewRequired": True, "warningCodes": ["NO_CANDIDATES"]})
        return ModelReply({"lines": result}, "fixture", 100, 100, 1)


def run():
    assert "site-packages" in __import__("ai_worker").__file__
    mode, base, run_id, context_hash = sys.argv[1:5]
    request = GraphRequest(run_id, context_hash)
    ledger_path = Path("/tmp/graph-" + run_id + ".json")
    data = json.loads(ledger_path.read_text()) if ledger_path.exists() else {"calls": 0}
    def save(): ledger_path.write_text(json.dumps(data))
    plan = execution_plan("https://fixture.invalid/chat", "fixture", "1", "2", "USD", "1", "max_completion_tokens")

    class Core(GraphCoreClient):
        def _call(self, r, suffix, body, document=None):
            try:
                return super()._call(r, suffix, body, document)
            except (WorkerFailure, AdvisoryFailure) as exc:
                print(json.dumps({"fixtureRoute": suffix, "errorCode": exc.code}), flush=True)
                raise
        def claim(self, r):
            claim = super().claim(r)
            if claim["disposition"] == "CLAIMED":
                data["claim"] = claim
                save()
            return claim
        def writes(self, r, t, writes):
            replies = super().writes(r, t, writes)
            if mode == "kill-resume" and any(w["channel"] == "__resume__" for w in writes):
                import signal
                os.kill(os.getpid(), signal.SIGKILL)
            return replies

    core = Core(base, os.environ["GRAPH_FIXTURE_WORKER_TOKEN"])
    model = RichModel(data, save)
    if mode.startswith("broker-") or mode == "kill-resume":
        from ai_worker.infrastructure.rabbit_consumer import RabbitConsumer
        segment = sys.argv[6]
        target = int(sys.argv[7])
        rabbit_host = os.environ.get("P406_RABBIT_HOST", "host.docker.internal")

        class Consumer(RabbitConsumer):
            def __init__(self, *args):
                super().__init__(*args)
                self.acks = 0
            def _settle(self, channel, tag, failure, body=b""):
                super()._settle(channel, tag, failure, body)
                if failure is None and not self.stopping.is_set():
                    self.acks += 1
                    if self.acks == target:
                        self._stop()
            def _opened(self, connection):
                super()._opened(connection)
                connection.ioloop.call_later(int(os.environ.get("P406_FIXTURE_DEADLINE", "45")),
                                             lambda: self._stop("FIXTURE_DEADLINE"))

        settings = {"host": rabbit_host, "port": sys.argv[5], "vhost": "/",
                    "username": os.environ.get("P406_RABBIT_USER", "graph-test"),
                    "password": os.environ.get("P406_RABBIT_PASSWORD", "graph-test-secret"),
                    "exchange": "invoice.graph", "queue": "invoice.graph." + segment, "routing_key": "ai-review-v2." + segment}
        consumer = Consumer(build_graph_processor(core, model, plan), settings,
                            GraphRequest.decode if segment == "start" else GraphRequest.decode_resume)
        consumer.run()
        assert consumer.acks == target
        print(json.dumps({"acks": consumer.acks, "modelCalls": data["calls"]}))
        return
    if mode == "restore":
        claim = data["claim"]
        session = GraphSession(core, request, claim["token"], claim["context"], model, plan, lambda: False)
        result = LangGraphRuntime().execute(session)
        assert result["disposition"] == "WAITING_HUMAN" and data["calls"] == 1
    else:
        try:
            build_graph_processor(core, model, plan).process(request, lambda: False)
        except WorkerFailure as exc:
            if mode != "break-wait" or exc.code != "CORE_UNAVAILABLE":
                raise
    print(json.dumps({"modelCalls": data["calls"], "mode": mode}))


if __name__ == "__main__":
    run()
