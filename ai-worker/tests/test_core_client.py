import asyncio
import hashlib
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from ai_worker.application.execution import Document, WorkerFailure
from ai_worker.infrastructure.core_client import CoreClient


@pytest.fixture
def server():
    state = {"status": 200, "media": "application/json", "body": b'{"disposition":"STALE"}', "hits": [], "slow": False}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_POST(self):
            state["hits"].append(self.path)
            self.rfile.read(int(self.headers["Content-Length"]))
            self.send_response(state["status"])
            self.send_header("Content-Type", state["media"])
            if state["status"] == 302:
                self.send_header("Location", "/redirect-target")
            self.send_header("Content-Length", str(len(state["body"])))
            self.end_headers()
            try:
                if state["slow"]:
                    for _ in range(24):
                        self.wfile.write(b" ")
                        self.wfile.flush()
                        time.sleep(0.6)
                else:
                    self.wfile.write(state["body"])
            except (BrokenPipeError, ConnectionResetError):
                pass
    http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=http.serve_forever)
    thread.start()
    try:
        yield CoreClient(f"http://127.0.0.1:{http.server_port}", "t" * 40), state
    finally:
        http.shutdown()
        http.server_close()
        thread.join(timeout=2)
        assert not thread.is_alive()


def test_redirect_never_forwards_bearer(server):
    client, state = server
    state["status"] = 302
    with pytest.raises(WorkerFailure):
        asyncio.run(client._post("/claim", {}))
    assert state["hits"] == ["/claim"]


def test_json_cap_and_duplicate_keys(server):
    client, state = server
    state["body"] = b"x" * (256 * 1024 + 1)
    with pytest.raises(WorkerFailure, match="RESPONSE_TOO_LARGE"):
        asyncio.run(client._post("/claim", {}))
    state["body"] = b'{"disposition":"STALE","disposition":"BUSY"}'
    with pytest.raises(WorkerFailure, match="INVALID_PROTOCOL"):
        asyncio.run(client._post("/claim", {}))


def test_source_is_checked_against_authoritative_metadata(server):
    client, state = server
    raw = b"%PDF-original"
    doc = Document("doc", "rev", "a.pdf", "application/pdf", len(raw), hashlib.sha256(raw).hexdigest())
    state.update(media=doc.media_type, body=raw)
    assert asyncio.run(client._post("/source", {}, doc)) == raw
    state["body"] = b"%PDF-corrupted"
    with pytest.raises(WorkerFailure):
        asyncio.run(client._post("/source", {}, doc))


def test_slow_trickle_has_total_deadline(server):
    client, state = server
    state.update(slow=True, body=b" " * 24)
    started = time.monotonic()
    with pytest.raises(WorkerFailure, match="CORE_UNAVAILABLE"):
        asyncio.run(client._post("/claim", {}))
    assert 9 < time.monotonic() - started < 12


@pytest.mark.parametrize("url", ["http://user:pass@localhost", "http://localhost/path", "http://localhost?x=y",
                                  "file:///tmp/file", "http://localhost/#fragment"])
def test_config_origin_is_fixed(url):
    with pytest.raises(WorkerFailure, match="INVALID_CONFIGURATION"):
        CoreClient(url, "t" * 40)
