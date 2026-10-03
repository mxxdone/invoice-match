import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from ai_worker.application.execution import ProcessDelivery, Request, WorkerFailure, strict_json
from ai_worker.domain.errors import ParseFailure


def document():
    return {"documentId": str(uuid4()), "sourceDraftRevisionId": str(uuid4()), "fileName": "test.pdf",
            "mediaType": "application/pdf", "sizeBytes": 12, "checksum": "a" * 64}


def message(count=1):
    return {"eventId": str(uuid4()), "analysisRunId": str(uuid4()), "invoiceCaseId": str(uuid4()),
            "evidenceBundleId": str(uuid4()), "inputVersion": 1, "evidencePayloadHash": "b" * 64,
            "workflowVersion": "document-parser-v1", "documents": sorted([document() for _ in range(count)],
                                                                        key=lambda d: d["documentId"])}


def decode(node):
    return Request.decode(json.dumps(node).encode(), node["eventId"], "application/json",
                          "InvoiceAnalysisRequested", "UTF-8")


def lease():
    return (datetime.now(timezone.utc) + timedelta(seconds=120)).isoformat()


class Core:
    def __init__(self, node):
        self.node = node
        self.claim_reply = {"disposition": "CLAIMED", "claimToken": str(uuid4()), "leaseUntil": lease(),
                            **{k: node[k] for k in ("invoiceCaseId", "evidenceBundleId", "inputVersion",
                               "evidencePayloadHash", "workflowVersion", "documents")}}
        self.saved = []
        self.fail_after_save = False
        self.stale = False

    def claim(self, request):
        return self.claim_reply

    def heartbeat(self, *_):
        return {"leaseUntil": lease()}

    def failure(self, *_):
        raise WorkerFailure("CORE_UNAVAILABLE")

    def defer(self, *_):
        raise WorkerFailure("CORE_UNAVAILABLE")

    def source(self, *_):
        return b"%PDF-fixture"

    def result(self, request, token, document, payload):
        self.saved.append(payload)
        if self.fail_after_save:
            raise WorkerFailure("CORE_UNAVAILABLE")
        if self.stale:
            return {"disposition": "STALE"}
        final = len(self.saved) == len(request.documents)
        return {"disposition": "ACCEPTED", "runStatus": "COMPLETED" if final else "RUNNING"}


class Parser:
    def parse(self, *_):
        return {"fixture": True}


@pytest.mark.parametrize("mutation", [lambda n: n.update(inputVersion=True), lambda n: n.update(inputVersion="1"),
    lambda n: n.update(extra="url"), lambda n: n.update(evidencePayloadHash="x" * 64),
    lambda n: n.update(documents=n["documents"] * 2), lambda n: n["documents"][0].update(sizeBytes=True)])
def test_message_is_strict(mutation):
    node = message()
    mutation(node)
    with pytest.raises(WorkerFailure):
        decode(node)


@pytest.mark.parametrize("raw", [b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":1e999}', b'[]', b'\xff'])
def test_strict_json(raw):
    with pytest.raises(WorkerFailure):
        strict_json(raw, 64 * 1024)


@pytest.mark.parametrize("disposition", ["STALE", "ALREADY_FINISHED"])
def test_terminal_claim_needs_no_source(disposition):
    node = message()
    core = Core(node)
    core.claim_reply = {"disposition": disposition}
    ProcessDelivery(core, Parser()).process(decode(node), lambda: False)
    assert core.saved == []


def test_all_documents_must_be_durable_before_success():
    node = message(2)
    core = Core(node)
    ProcessDelivery(core, Parser()).process(decode(node), lambda: False)
    assert len(core.saved) == 2


def test_saved_result_response_loss_preserves_delivery():
    node = message(2)
    core = Core(node)
    core.fail_after_save = True
    with pytest.raises(WorkerFailure, match="CORE_UNAVAILABLE"):
        ProcessDelivery(core, Parser()).process(decode(node), lambda: False)
    assert len(core.saved) == 1


def test_parser_failure_is_a_durable_result():
    class Broken(Parser):
        def parse(self, *_):
            raise ParseFailure("PDF_CORRUPT")
    node = message()
    core = Core(node)
    ProcessDelivery(core, Broken()).process(decode(node), lambda: False)
    assert core.saved == [{"outcome": "FAILURE", "result": None, "errorCode": "PDF_CORRUPT"}]


def test_source_failure_is_never_a_parser_result():
    node = message()
    core = Core(node)
    def unavailable(*_):
        raise WorkerFailure("CORE_UNAVAILABLE")
    core.source = unavailable
    with pytest.raises(WorkerFailure):
        ProcessDelivery(core, Parser()).process(decode(node), lambda: False)
    assert core.saved == []


def test_changed_manifest_is_rejected_before_read():
    node = message()
    core = Core(node)
    core.claim_reply = {**core.claim_reply, "documents": [document()]}
    with pytest.raises(WorkerFailure, match="CLAIM_MISMATCH"):
        ProcessDelivery(core, Parser()).process(decode(node), lambda: False)


def test_stale_result_stops_remaining_documents():
    node = message(2)
    core = Core(node)
    core.stale = True
    ProcessDelivery(core, Parser()).process(decode(node), lambda: False)
    assert len(core.saved) == 1


def test_busy_is_not_terminal_ack_evidence():
    node = message()
    core = Core(node)
    core.claim_reply = {"disposition": "BUSY", "leaseUntil": lease()}
    with pytest.raises(WorkerFailure, match="CORE_UNAVAILABLE"):
        ProcessDelivery(core, Parser()).process(decode(node), lambda: False)


def test_response_loss_is_ackable_only_after_durable_failure_checkpoint():
    node = message(2)
    core = Core(node)
    core.fail_after_save = True
    failures = []
    def checkpoint(request, token, code):
        failures.append((token, code))
        return {"disposition": "CHECKPOINTED", "runStatus": "RETRY_SCHEDULED"}
    core.failure = checkpoint
    ProcessDelivery(core, Parser()).process(decode(node), lambda: False)
    assert len(core.saved) == 1
    assert failures == [(core.claim_reply["claimToken"], "CORE_UNAVAILABLE")]


def test_busy_delivery_needs_durable_deferred_dispatch():
    node = message()
    core = Core(node)
    core.claim_reply = {"disposition": "BUSY", "leaseUntil": lease()}
    core.defer = lambda _: {"disposition": "CHECKPOINTED", "runStatus": "RUNNING"}
    ProcessDelivery(core, Parser()).process(decode(node), lambda: False)
    assert not core.saved


def test_invalid_checkpoint_cannot_be_ack_evidence():
    node = message()
    core = Core(node)
    core.fail_after_save = True
    core.failure = lambda *_: {"disposition": "CHECKPOINTED", "runStatus": "MADE_UP"}
    with pytest.raises(WorkerFailure, match="INVALID_PROTOCOL"):
        ProcessDelivery(core, Parser()).process(decode(node), lambda: False)


def test_shutdown_after_parse_never_posts_result():
    stopping = False
    class Slow(Parser):
        def parse(self, *_):
            nonlocal stopping
            stopping = True
            return {}
    node = message()
    core = Core(node)
    with pytest.raises(WorkerFailure, match="SHUTTING_DOWN"):
        ProcessDelivery(core, Slow()).process(decode(node), lambda: stopping)
    assert core.saved == []
