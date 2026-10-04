"""Small explicit schema validators for advisory output, independent of provider SDKs."""
from __future__ import annotations
from datetime import date
from decimal import Decimal, InvalidOperation
import re
from ai_worker.domain.advisory import AdvisoryFailure, SourceSegment


def fail():
    raise AdvisoryFailure("AI_SCHEMA_INVALID")


def exact(value, expected: set[str]) -> dict:
    if not isinstance(value, dict) or set(value) != expected:
        fail()
    return value


def bounded_text(value, maximum=500) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > maximum or "\x00" in value:
        fail()
    return value


def source_quote(value, segments: dict[str, SourceSegment]) -> str:
    value = exact(value, {"segmentId", "start", "end"})
    segment = segments.get(value["segmentId"]) if isinstance(value["segmentId"], str) else None
    if segment is None or type(value["start"]) is not int or type(value["end"]) is not int \
            or not 0 <= value["start"] < value["end"] <= len(segment.text) or value["end"]-value["start"] > 500:
        fail()
    return bounded_text(segment.text[value["start"]:value["end"]])


def normalize(raw: str, kind: str) -> str:
    raw = raw.strip()
    if kind in {"quantity", "unitPrice"}:
        number = raw
        if kind == "quantity":
            number = re.sub(r"\s*(개|EA|PCS)$", "", number, flags=re.IGNORECASE)
        else:
            number = re.sub(r"^(₩|KRW)\s*|\s*원$", "", number, flags=re.IGNORECASE)
        if re.fullmatch(r"(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]+)?", number) is None:
            fail()
        try:
            value = Decimal(number.replace(",", ""))
            bound = 2**31-1 if kind == "quantity" else 2**63-1
            if value != value.to_integral_value() or not (1 if kind == "quantity" else 0) <= value <= bound:
                fail()
            return str(int(value))
        except InvalidOperation:
            fail()
    if kind == "invoiceDate":
        parts = re.fullmatch(r"(\d{4})[./-](\d{1,2})[./-](\d{1,2})", raw)
        if parts is None:
            fail()
        try:
            return date(*(int(v) for v in parts.groups())).isoformat()
        except ValueError:
            fail()
    if kind == "currency":
        mapping = {"₩": "KRW", "원": "KRW", "KRW": "KRW", "USD": "USD", "EUR": "EUR"}
        if raw not in mapping:
            fail()
        return mapping[raw]
    return bounded_text(raw)


def validate_extraction(value: dict, sources: tuple[SourceSegment, ...]) -> dict:
    exact(value, {"fields", "lines", "warnings"})
    segments = {s.segment_id: s for s in sources}
    if len(segments) != len(sources):
        fail()
    if not isinstance(value["fields"], list) or len(value["fields"]) > 4 \
            or not isinstance(value["lines"], list) or len(value["lines"]) > 100 \
            or not isinstance(value["warnings"], list) or len(value["warnings"]) > 10:
        fail()
    names = set()
    for field in value["fields"]:
        exact(field, {"name", "value", "source"})
        name = field["name"]
        if not isinstance(name, str) or name not in {"invoiceNumber", "supplierName", "invoiceDate", "currency"} or name in names:
            fail()
        names.add(name)
        if bounded_text(field["value"]) != normalize(source_quote(field["source"], segments), name):
            fail()
    numbers = set()
    for line in value["lines"]:
        exact(line, {"lineNumber", "rawItemName", "quantity", "unitPrice"})
        number = line["lineNumber"]
        if type(number) is not int or not 1 <= number <= 100 or number in numbers:
            fail()
        numbers.add(number)
        for kind in ("rawItemName", "quantity", "unitPrice"):
            field = exact(line[kind], {"value", "source"})
            if bounded_text(field["value"]) != normalize(source_quote(field["source"], segments), kind):
                fail()
    if any(not isinstance(w, str) or w not in {"MISSING_FIELDS", "AMBIGUOUS_LAYOUT", "EMPTY_DOCUMENT", "DOCUMENT_CONFLICT"} for w in value["warnings"]):
        fail()
    if not value["fields"] and not value["lines"] and "EMPTY_DOCUMENT" not in value["warnings"]:
        fail()
    return value


def object_schema(properties):
    return {"type": "object", "properties": properties, "required": list(properties), "additionalProperties": False}


SOURCE_SCHEMA = object_schema({"segmentId": {"type": "string"}, "start": {"type": "integer"}, "end": {"type": "integer"}})
VALUE_SCHEMA = object_schema({"value": {"type": "string"}, "source": SOURCE_SCHEMA})
EXTRACTION_SCHEMA = object_schema({
    "fields": {"type": "array", "items": object_schema({"name": {"type": "string", "enum": ["invoiceNumber", "supplierName", "invoiceDate", "currency"]},
        "value": {"type": "string"}, "source": SOURCE_SCHEMA})},
    "lines": {"type": "array", "items": object_schema({"lineNumber": {"type": "integer"}, "rawItemName": VALUE_SCHEMA,
        "quantity": VALUE_SCHEMA, "unitPrice": VALUE_SCHEMA})},
    "warnings": {"type": "array", "items": {"type": "string", "enum": ["MISSING_FIELDS", "AMBIGUOUS_LAYOUT", "EMPTY_DOCUMENT", "DOCUMENT_CONFLICT"]}},
})
