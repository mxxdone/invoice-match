"""Explicit embedding provider/model. Float wire, one input, fixed origin, bounded I/O."""
import asyncio
import math
import time
import aiohttp
from ai_worker.application.execution import strict_json,WorkerFailure
from ai_worker.domain.advisory import AdvisoryFailure
from ai_worker.infrastructure.structured_model import ChatStructuredModel

class EmbeddingModel:
    def __init__(self,url,key,model,version,dimension,*,allow_loopback_test_endpoint=False):
        configured=ChatStructuredModel(url,key,model,allow_loopback_test_endpoint=allow_loopback_test_endpoint)
        if not version or len(version)>100 or type(dimension) is not int or not 1<=dimension<=3072:raise AdvisoryFailure("AI_CONFIGURATION")
        self.url,self.headers,self.model,self.version,self.dimension=configured.url,configured.headers,model,version,dimension
    def embed(self,query):
        if not isinstance(query,str) or not query.strip() or len(query)>200:raise AdvisoryFailure("AI_INPUT_LIMIT")
        return asyncio.run(self._embed(query))
    async def _embed(self,query):
        start=time.monotonic()
        try:
            async with asyncio.timeout(20):
                timeout=aiohttp.ClientTimeout(total=20,connect=2,sock_read=10,ceil_threshold=1000)
                async with aiohttp.ClientSession(timeout=timeout,trust_env=False,auto_decompress=False,cookie_jar=aiohttp.DummyCookieJar(),headers=self.headers) as session:
                    async with session.post(self.url,json={"model":self.model,"input":query,"encoding_format":"float","dimensions":self.dimension},allow_redirects=False) as response:
                        if response.status==429:raise AdvisoryFailure("AI_RATE_LIMIT")
                        if response.status in {401,403}:raise AdvisoryFailure("AI_CONFIGURATION")
                        if response.status!=200 or response.headers.get("Content-Type","").split(";")[0]!="application/json" or response.headers.get("Content-Encoding","identity")!="identity":raise AdvisoryFailure("AI_FAILED")
                        data=bytearray()
                        async for chunk in response.content.iter_chunked(16384):
                            if len(data)+len(chunk)>128000:raise AdvisoryFailure("AI_SCHEMA_INVALID")
                            data.extend(chunk)
            n=strict_json(bytes(data),128000);vectors=n.get("data");usage=n.get("usage",{})
            if n.get("model")!=self.model or not isinstance(vectors,list) or len(vectors)!=1 or not isinstance(vectors[0],dict) or type(vectors[0].get("index")) is not int or vectors[0]["index"]!=0:raise AdvisoryFailure("AI_SCHEMA_INVALID")
            vector=vectors[0].get("embedding");tokens=usage.get("prompt_tokens")
            if not isinstance(vector,list) or len(vector)!=self.dimension or any(type(v) not in (float,int) or not math.isfinite(v) or abs(v)>1000000 for v in vector) \
                or not 1e-12<=sum(v*v for v in vector)<=1e12 or type(tokens) is not int or not 0<=tokens<=1000 or usage.get("total_tokens")!=tokens:raise AdvisoryFailure("AI_SCHEMA_INVALID")
            return {"embedding":vector,"calls":[{"model":self.model,"inputTokens":tokens,"outputTokens":0,"latencyMs":int((time.monotonic()-start)*1000)}]}
        except AdvisoryFailure:raise
        except TimeoutError as exc:raise AdvisoryFailure("AI_TIMEOUT") from exc
        except WorkerFailure as exc:raise AdvisoryFailure("AI_SCHEMA_INVALID") from exc
        except (aiohttp.ClientError,OSError,ValueError,TypeError,RecursionError,OverflowError) as exc:raise AdvisoryFailure("AI_FAILED") from exc
