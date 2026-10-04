"""Configured Chat Completions wire. No selected/default provider, model or live credentials."""
from __future__ import annotations
import asyncio
import json
import time
from urllib.parse import urlsplit
import aiohttp
from ai_worker.application.execution import strict_json, WorkerFailure
from ai_worker.domain.advisory import AdvisoryFailure, ModelReply


class ChatStructuredModel:
    def __init__(self, url: str, key: str, model: str, *, token_parameter: str = "max_completion_tokens",
                 api_key_header: bool = False, timeout_seconds: float = 40, allow_loopback_test_endpoint: bool = False):
        try:
            p = urlsplit(url)
            valid = (p.scheme == "https" or (allow_loopback_test_endpoint and p.scheme == "http" and p.hostname == "127.0.0.1")) \
                    and p.hostname and not p.username and not p.password and not p.fragment and p.port != 0
        except ValueError:
            valid = False
        if not valid or not key or any(ord(c) < 33 for c in key) or not model or len(model) > 100 \
                or any(ord(c) < 33 for c in model) or token_parameter not in {"max_tokens", "max_completion_tokens"} \
                or not 0 < timeout_seconds <= 40:
            raise AdvisoryFailure("AI_CONFIGURATION")
        self.url, self.model, self.token_parameter, self.timeout = url, model, token_parameter, timeout_seconds
        self.headers = {"api-key": key} if api_key_header else {"Authorization": "Bearer " + key}

    def generate(self, name: str, system: str, payload: dict, schema: dict, max_tokens: int) -> ModelReply:
        if type(max_tokens) is not int or not 1 <= max_tokens <= 2000:
            raise AdvisoryFailure("AI_BUDGET_EXHAUSTED")
        body = {"model": self.model, "messages": [{"role": "system", "content": system},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False, separators=(",", ":"))}],
                self.token_parameter: max_tokens, "stream": False, "store": False,
                "response_format": {"type": "json_schema", "json_schema": {"name": name, "strict": True, "schema": schema}}}
        if len(json.dumps(body, ensure_ascii=False).encode()) > 80000:
            raise AdvisoryFailure("AI_INPUT_LIMIT")
        return asyncio.run(self._generate(body, max_tokens))

    async def _generate(self, body: dict, max_tokens: int) -> ModelReply:
        start = time.monotonic()
        try:
            async with asyncio.timeout(self.timeout):
                timeout = aiohttp.ClientTimeout(total=self.timeout, connect=2, sock_read=self.timeout, ceil_threshold=1000)
                async with aiohttp.ClientSession(timeout=timeout, trust_env=False, auto_decompress=False,
                        cookie_jar=aiohttp.DummyCookieJar(), headers=self.headers) as session:
                    async with session.post(self.url, json=body, allow_redirects=False) as response:
                        if response.status == 429:
                            raise AdvisoryFailure("AI_RATE_LIMIT")
                        if response.status != 200 or response.headers.get("Content-Type", "").split(";")[0] != "application/json" \
                                or response.headers.get("Content-Encoding", "identity") != "identity":
                            raise AdvisoryFailure("AI_FAILED")
                        data = bytearray()
                        async for chunk in response.content.iter_chunked(16384):
                            if len(data) + len(chunk) > 128000:
                                raise AdvisoryFailure("AI_SCHEMA_INVALID")
                            data.extend(chunk)
            result = strict_json(bytes(data), 128000)
            choices, usage = result.get("choices"), result.get("usage")
            if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict) \
                    or choices[0].get("finish_reason") != "stop" or not isinstance(usage, dict):
                raise AdvisoryFailure("AI_SCHEMA_INVALID")
            message = choices[0].get("message")
            if not isinstance(message, dict) or message.get("refusal") or message.get("tool_calls") \
                    or not isinstance(message.get("content"), str):
                raise AdvisoryFailure("AI_SCHEMA_INVALID")
            prompt, completion = usage.get("prompt_tokens"), usage.get("completion_tokens")
            model = result.get("model")
            if type(prompt) is not int or type(completion) is not int or not 0 <= prompt <= 40000 \
                    or not 0 <= completion <= max_tokens or not isinstance(model, str) or not 1 <= len(model) <= 100:
                raise AdvisoryFailure("AI_SCHEMA_INVALID")
            output = strict_json(message["content"].encode(), 64000)
            return ModelReply(output, model, prompt, completion, int((time.monotonic()-start)*1000))
        except AdvisoryFailure:
            raise
        except WorkerFailure as exc:
            raise AdvisoryFailure("AI_SCHEMA_INVALID") from exc
        except TimeoutError as exc:
            raise AdvisoryFailure("AI_TIMEOUT") from exc
        except (aiohttp.ClientError, OSError, ValueError, TypeError, RecursionError) as exc:
            raise AdvisoryFailure("AI_FAILED") from exc
