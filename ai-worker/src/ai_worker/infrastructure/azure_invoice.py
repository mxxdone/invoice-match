"""Fixed-origin binary submit and bounded polling; no storage URL or redirects."""
from __future__ import annotations
import asyncio
import re
from uuid import UUID
from urllib.parse import urlsplit
import aiohttp
from ai_worker.application.execution import strict_json, WorkerFailure
from ai_worker.domain.advisory import AdvisoryFailure


class AzureInvoiceRecognizer:
    def __init__(self, endpoint: str, key: str, *, timeout_seconds: float = 40,
                 poll_seconds: float = 1, allow_loopback_test_endpoint: bool = False):
        try:
            p = urlsplit(endpoint)
            scheme = p.scheme == "https" or (allow_loopback_test_endpoint and p.scheme == "http" and p.hostname == "127.0.0.1")
            valid = scheme and p.hostname and not p.username and not p.password and p.path in {"", "/"} \
                    and not p.query and not p.fragment and p.port != 0
        except ValueError:
            valid = False
        if not valid or not key or any(ord(c) < 33 for c in key) or not 0 < timeout_seconds <= 40 or not 0 <= poll_seconds <= 5:
            raise AdvisoryFailure("OCR_CONFIGURATION")
        self.origin, self.key = endpoint.rstrip("/"), key
        self.timeout, self.poll = timeout_seconds, poll_seconds

    def recognize(self, data: bytes) -> dict:
        return asyncio.run(self._recognize(data))

    def _operation(self, value: str) -> str:
        try:
            parsed, origin = urlsplit(value), urlsplit(self.origin)
            valid = parsed.scheme == origin.scheme and parsed.netloc == origin.netloc and not parsed.fragment \
                    and parsed.query == "api-version=2024-11-30" and re.fullmatch(
                    r"/documentintelligence/documentModels/prebuilt-invoice/analyzeResults/[0-9a-fA-F-]{36}", parsed.path)
            valid = valid and str(UUID(parsed.path.rsplit("/", 1)[-1])) == parsed.path.rsplit("/", 1)[-1].lower()
        except ValueError:
            valid = False
        if not valid:
            raise AdvisoryFailure("OCR_INVALID_RESPONSE")
        return value

    async def _read(self, response) -> dict:
        if response.status == 429:
            raise AdvisoryFailure("OCR_RATE_LIMIT")
        if response.status in {401, 403}:
            raise AdvisoryFailure("OCR_CONFIGURATION")
        if response.status != 200 or response.headers.get("Content-Type", "").split(";")[0] != "application/json" \
                or response.headers.get("Content-Encoding", "identity") != "identity":
            raise AdvisoryFailure("OCR_FAILED")
        body = bytearray()
        async for chunk in response.content.iter_chunked(16384):
            if len(body) + len(chunk) > 1024 * 1024:
                raise AdvisoryFailure("OCR_INVALID_RESPONSE")
            body.extend(chunk)
        try:
            return strict_json(bytes(body), 1024 * 1024)
        except WorkerFailure as exc:
            raise AdvisoryFailure("OCR_INVALID_RESPONSE") from exc

    async def _recognize(self, data: bytes) -> dict:
        try:
            async with asyncio.timeout(self.timeout):
                timeout = aiohttp.ClientTimeout(total=10, connect=2, sock_read=8, ceil_threshold=1000)
                async with aiohttp.ClientSession(timeout=timeout, trust_env=False, auto_decompress=False,
                        cookie_jar=aiohttp.DummyCookieJar(), headers={"Ocp-Apim-Subscription-Key": self.key}) as session:
                    path = "/documentintelligence/documentModels/prebuilt-invoice:analyze?api-version=2024-11-30&stringIndexType=unicodeCodePoint"
                    async with session.post(self.origin + path, data=data, headers={"Content-Type": "application/pdf"},
                                            allow_redirects=False) as response:
                        if response.status == 429:
                            raise AdvisoryFailure("OCR_RATE_LIMIT")
                        if response.status in {401, 403}:
                            raise AdvisoryFailure("OCR_CONFIGURATION")
                        if response.status != 202:
                            raise AdvisoryFailure("OCR_FAILED")
                        operation = self._operation(response.headers.get("Operation-Location", ""))
                    for _ in range(40):
                        await asyncio.sleep(self.poll)
                        async with session.get(operation, allow_redirects=False) as response:
                            result = await self._read(response)
                        if result.get("status") == "succeeded" and isinstance(result.get("analyzeResult"), dict):
                            return result["analyzeResult"]
                        if result.get("status") not in {"running", "notStarted"}:
                            raise AdvisoryFailure("OCR_FAILED")
                    raise AdvisoryFailure("OCR_TIMEOUT")
        except AdvisoryFailure:
            raise
        except TimeoutError as exc:
            raise AdvisoryFailure("OCR_TIMEOUT") from exc
        except (aiohttp.ClientError, OSError, ValueError) as exc:
            raise AdvisoryFailure("OCR_FAILED") from exc
