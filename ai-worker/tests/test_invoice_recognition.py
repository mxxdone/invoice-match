import hashlib
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4
import pytest
from ai_worker.application.recognition import RecognizeInvoice, normalize_ocr
from ai_worker.domain.advisory import AdvisoryFailure
from ai_worker.infrastructure.azure_invoice import AzureInvoiceRecognizer


def result():
    text = "청구서 😀 INV-100"
    polygon = [0, 0, 4, 0, 4, 1, 0, 1]
    span = {"offset": 0, "length": len(text)}
    return {"apiVersion": "2024-11-30", "modelId": "prebuilt-invoice", "stringIndexType": "unicodeCodePoint",
            "content": text, "pages": [{"pageNumber": 1, "width": 8.3, "height": 11.7, "unit": "inch", "spans": [span],
                "lines": [{"content": text, "spans": [span], "polygon": polygon}]}],
            "documents": [{"fields": {"InvoiceId": {"content": "INV-100", "spans": [{"offset": 6, "length": 7}],
                "confidence": 0.9, "boundingRegions": [{"pageNumber": 1, "polygon": polygon}]}}}]}


@pytest.fixture
def server():
    state = {"status": 202, "get_status": 200, "polls": 0, "hits": [], "operation": None,
             "body": {"status": "succeeded", "analyzeResult": result()}, "slow": False}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_POST(self):
            data = self.rfile.read(int(self.headers["Content-Length"]))
            state["hits"].append(("POST", self.path, data))
            self.send_response(state["status"])
            operation = state["operation"] or f"http://127.0.0.1:{self.server.server_port}/documentintelligence/documentModels/prebuilt-invoice/analyzeResults/{uuid4()}?api-version=2024-11-30"
            self.send_header("Operation-Location", operation)
            self.send_header("Content-Length", "0")
            self.end_headers()
        def do_GET(self):
            state["hits"].append(("GET", self.path))
            state["polls"] += 1
            body = state["body"] if isinstance(state["body"], bytes) else json.dumps(state["body"]).encode()
            self.send_response(state["get_status"])
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                if state["slow"]:
                    time.sleep(0.3)
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass
    http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=http.serve_forever)
    thread.start()
    try:
        yield f"http://127.0.0.1:{http.server_port}", state
    finally:
        http.shutdown(); http.server_close(); thread.join(timeout=2)
        assert not thread.is_alive()


def adapter(origin, **kwargs):
    return AzureInvoiceRecognizer(origin, "fixture-key", allow_loopback_test_endpoint=True, poll_seconds=0, **kwargs)


def test_real_binary_submit_poll_and_unicode_positions(server):
    origin, state = server
    data = b"%PDF-fixture"
    output = RecognizeInvoice(adapter(origin)).execute(str(uuid4()), data, hashlib.sha256(data).hexdigest(), 1)
    assert output["fields"][0]["content"] == "INV-100"
    assert output["pages"][0]["segments"][0]["text"] == "청구서 😀 INV-100"
    assert state["hits"][0][2] == data
    assert "stringIndexType=unicodeCodePoint" in state["hits"][0][1]
    assert state["polls"] == 1


@pytest.mark.parametrize("mutation", [
    lambda r: r.update(stringIndexType="utf16CodeUnit"),
    lambda r: r.update(apiVersion="2023-07-31"),
    lambda r: r.update(modelId="prebuilt-layout"),
    lambda r: r.update(content=None),
    lambda r: r.update(documents=None),
    lambda r: r["pages"][0].update(pageNumber=2),
    lambda r: r["pages"][0].update(width=float("nan")),
    lambda r: r["pages"][0]["lines"][0].update(content="invented"),
    lambda r: r["pages"][0]["lines"][0]["spans"][0].update(offset=-1),
    lambda r: r["pages"][0]["lines"][0].update(polygon=[0]*7),
    lambda r: r["pages"][0]["lines"][0].update(polygon=[999]*8),
    lambda r: r["documents"][0]["fields"]["InvoiceId"].update(confidence=1.1),
    lambda r: r["documents"][0]["fields"]["InvoiceId"].update(content="INV-999"),
    lambda r: r["documents"][0]["fields"]["InvoiceId"]["boundingRegions"][0].update(pageNumber=2),
    lambda r: r["documents"][0]["fields"]["InvoiceId"]["boundingRegions"][0].update(polygon=[-1]*8),
])
def test_invalid_provider_positions_are_fixed_failures(mutation):
    r = result(); mutation(r)
    with pytest.raises(AdvisoryFailure, match="OCR_INVALID_RESPONSE"):
        normalize_ocr(str(uuid4()), "0"*64, 1, r)


@pytest.mark.parametrize("operation", ["https://other.example/path", "http://127.0.0.1@other.example/path",
    "http://127.0.0.1:1/documentintelligence/documentModels/prebuilt-invoice/analyzeResults/"+"a"*36+"?api-version=2024-11-30",
    "/relative", "https://azure.example/path?secret=bad"])
def test_untrusted_operation_never_receives_key(server, operation):
    origin, state = server; state["operation"] = operation
    with pytest.raises(AdvisoryFailure, match="OCR_INVALID_RESPONSE"):
        adapter(origin).recognize(b"%PDF-fixture")
    assert state["polls"] == 0


@pytest.mark.parametrize("status,code", [(302, "OCR_FAILED"), (429, "OCR_RATE_LIMIT"), (500, "OCR_FAILED"), (401, "OCR_CONFIGURATION"), (403, "OCR_CONFIGURATION")])
def test_submit_failure_does_not_follow_or_retry(server, status, code):
    origin, state = server; state["status"] = status
    with pytest.raises(AdvisoryFailure, match=code):
        adapter(origin).recognize(b"%PDF-fixture")
    assert len(state["hits"]) == 1


def test_polling_and_response_limits(server):
    origin, state = server
    state["body"] = {"status": "running"}
    with pytest.raises(AdvisoryFailure, match="OCR_TIMEOUT"):
        adapter(origin).recognize(b"%PDF-fixture")
    assert state["polls"] == 40
    state["body"] = b"x" * (1024 * 1024 + 1)
    with pytest.raises(AdvisoryFailure, match="OCR_INVALID_RESPONSE"):
        adapter(origin).recognize(b"%PDF-fixture")
    state["body"] = b'{"status":"running","status":"succeeded"}'
    with pytest.raises(AdvisoryFailure, match="OCR_INVALID_RESPONSE"):
        adapter(origin).recognize(b"%PDF-fixture")
    state["body"] = {"status": "succeeded", "analyzeResult": result()}; state["slow"] = True
    start = time.monotonic()
    with pytest.raises(AdvisoryFailure, match="OCR_TIMEOUT"):
        adapter(origin, timeout_seconds=0.1).recognize(b"%PDF-fixture")
    assert time.monotonic() - start < 0.5


def test_f0_admission_precedes_any_provider_call():
    class Never:
        def recognize(self, data):
            pytest.fail("admission must reject before external call")
    service = RecognizeInvoice(Never())
    for data, pages in [(b"%PDF-fixture", 3), (b"%PDF-" + b"x"*(4*1024*1024), 1)]:
        with pytest.raises(AdvisoryFailure, match="OCR_LIMIT"):
            service.execute(str(uuid4()), data, hashlib.sha256(data).hexdigest(), pages)
    with pytest.raises(AdvisoryFailure, match="SOURCE_MISMATCH"):
        service.execute(str(uuid4()), b"%PDF-fixture", "0"*64, 1)
    with pytest.raises(AdvisoryFailure, match="OCR_CONFIGURATION"):
        AzureInvoiceRecognizer("http://127.0.0.1", "key")
