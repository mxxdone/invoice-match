"""Closed JSON codec for the pinned LangGraph checkpoint runtime.

Only JSON values, SDK tuples and the SDK Interrupt DTO can cross this boundary.
No pickle, dynamic imports, constructor names or object hooks are accepted.
Graph state must contain Core identities/references, never credentials or files.
"""

import json
import math

from langgraph.types import Interrupt

from ai_worker.application.graph_contract import GraphVersions


class GraphCheckpointSerializer:
    TYPE = GraphVersions().serializer
    MAX_BYTES = 262_144
    MAX_DEPTH = 64

    def dumps_typed(self, value):
        data = json.dumps(self._encode(value, 0), ensure_ascii=False,
                          allow_nan=False, separators=(",", ":")).encode("utf-8")
        if len(data) > self.MAX_BYTES:
            raise ValueError("GRAPH_CHECKPOINT_SIZE")
        return self.TYPE, data

    def loads_typed(self, value):
        kind, data = value
        if kind != self.TYPE:
            raise ValueError("GRAPH_CHECKPOINT_VERSION")
        if len(data) > self.MAX_BYTES:
            raise ValueError("GRAPH_CHECKPOINT_SIZE")
        try:
            encoded = json.loads(data)
        except (ValueError, RecursionError) as exc:
            raise ValueError("GRAPH_CHECKPOINT_JSON") from exc
        return self._decode(encoded, 0)

    def _encode(self, value, depth):
        self._depth(depth)
        if value is None or type(value) in (str, bool, int):
            return ["scalar", value]
        if type(value) is float and math.isfinite(value):
            return ["scalar", value]
        if type(value) in (list, tuple):
            tag = "list" if type(value) is list else "tuple"
            return [tag, [self._encode(v, depth + 1) for v in value]]
        if type(value) is dict and all(type(k) is str for k in value):
            return ["dict", {k: self._encode(v, depth + 1)
                             for k, v in sorted(value.items())}]
        if type(value) is Interrupt:
            if value.response_schema is not None or type(value.id) is not str:
                raise ValueError("GRAPH_CHECKPOINT_TYPE")
            return ["interrupt", {"id": value.id,
                                  "value": self._encode(value.value, depth + 1)}]
        raise ValueError("GRAPH_CHECKPOINT_TYPE")

    def _decode(self, value, depth):
        self._depth(depth)
        if type(value) is not list or len(value) != 2:
            raise ValueError("GRAPH_CHECKPOINT_TYPE")
        tag, body = value
        if tag == "scalar":
            if body is None or type(body) in (str, bool, int):
                return body
            if type(body) is float and math.isfinite(body):
                return body
        elif tag in ("list", "tuple") and type(body) is list:
            decoded = [self._decode(v, depth + 1) for v in body]
            return decoded if tag == "list" else tuple(decoded)
        elif tag == "dict" and type(body) is dict:
            return {k: self._decode(v, depth + 1) for k, v in body.items()}
        elif tag == "interrupt" and type(body) is dict:
            if set(body) == {"id", "value"} and type(body["id"]) is str:
                return Interrupt(id=body["id"], value=self._decode(body["value"], depth + 1))
        raise ValueError("GRAPH_CHECKPOINT_TYPE")

    def _depth(self, depth):
        if depth > self.MAX_DEPTH:
            raise ValueError("GRAPH_CHECKPOINT_DEPTH")
