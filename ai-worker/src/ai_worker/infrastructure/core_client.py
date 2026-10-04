"""Bounded, fixed-origin machine HTTP adapter. No redirects, cookies or proxies."""
from __future__ import annotations

import asyncio
import hashlib
from urllib.parse import urlsplit

import aiohttp

from ai_worker.application.execution import Document, Request, WorkerFailure, strict_json


class CoreClient:
    json_cap = 256 * 1024
    def __init__(self, base_url: str, token: str) -> None:
        try:
            parsed = urlsplit(base_url)
            valid = (parsed.scheme in {"http", "https"} and parsed.hostname and parsed.port != 0
                     and not parsed.username and not parsed.password and parsed.path in {"", "/"}
                     and not parsed.query and not parsed.fragment)
        except ValueError:
            valid = False
        if not valid or len(token) < 32 or not token.isascii() or any(ord(c) < 33 for c in token):
            raise WorkerFailure("INVALID_CONFIGURATION")
        self.base_url, self.token = base_url.rstrip("/"), token

    async def _post(self, path: str, body: dict, document: Document | None = None):
        cap = document.size if document else self.json_cap
        timeout = aiohttp.ClientTimeout(total=25 if document else 10, connect=2,
                                         sock_read=10, ceil_threshold=1000)
        try:
            async with aiohttp.ClientSession(timeout=timeout, trust_env=False, auto_decompress=False,
                    cookie_jar=aiohttp.DummyCookieJar(), headers={"Authorization": "Bearer " + self.token}) as session:
                async with session.post(self.base_url + path, json=body, allow_redirects=False) as response:
                    if response.status in {401, 403}:
                        raise WorkerFailure("CORE_AUTHENTICATION_FAILED")
                    if response.status == 429 or 500 <= response.status < 600:
                        raise WorkerFailure("CORE_TRANSIENT")
                    if response.status != 200:
                        await self._error_response(response)
                    if response.status != 200 or response.headers.get("Content-Encoding", "identity") != "identity":
                        raise WorkerFailure("CORE_REQUEST_FAILED")
                    media = response.headers.get("Content-Type", "").split(";", 1)[0].strip()
                    if media != (document.media_type if document else "application/json"):
                        raise WorkerFailure("INVALID_PROTOCOL")
                    data = bytearray()
                    async for chunk in response.content.iter_chunked(64 * 1024):
                        if len(data) + len(chunk) > cap:
                            raise WorkerFailure("RESPONSE_TOO_LARGE")
                        data.extend(chunk)
                    raw = bytes(data)
                    if document:
                        if len(raw) != document.size or hashlib.sha256(raw).hexdigest() != document.checksum:
                            raise WorkerFailure("SOURCE_MISMATCH")
                        signature = b"%PDF-" if media == "application/pdf" else b"PK\x03\x04"
                        if not raw.startswith(signature):
                            raise WorkerFailure("SOURCE_MISMATCH")
                        return raw
                    return self._decode_response(raw, cap)
        except WorkerFailure:
            raise
        except (aiohttp.ClientError, TimeoutError, OSError, ValueError) as exc:
            raise WorkerFailure("CORE_UNAVAILABLE") from exc

    async def _error_response(self,response):
        raise WorkerFailure("CORE_REQUEST_FAILED")

    def _decode_response(self, raw, cap):
        return strict_json(raw, cap)

    def _call(self, request: Request, suffix: str, body: dict, document=None):
        return asyncio.run(self._post("/internal/analysis-runs/" + request.run_id + suffix, body, document))

    def claim(self, request: Request) -> dict:
        return self._call(request, "/claim", {**request.input(), "eventId": request.event_id,
                                               "workflowVersion": request.workflow})

    def heartbeat(self, request: Request, token: str) -> dict:
        return self._call(request, "/heartbeat", {**request.input(), "claimToken": token})

    def source(self, request: Request, token: str, document: Document) -> bytes:
        return self._call(request, "/documents/" + document.document_id + "/source",
                          {**request.input(), "claimToken": token}, document)

    def result(self, request: Request, token: str, document: Document, payload: dict) -> dict:
        return self._call(request, "/results", {**request.input(), "claimToken": token,
                          "documentId": document.document_id, **payload})

    def failure(self, request: Request, token: str, code: str) -> dict:
        return self._call(request, "/failures", {**request.input(), "claimToken": token, "errorCode": code})

    def defer(self, request: Request) -> dict:
        return self._call(request, "/defer", {**request.input(), "eventId": request.event_id,
                                             "workflowVersion": request.workflow})
