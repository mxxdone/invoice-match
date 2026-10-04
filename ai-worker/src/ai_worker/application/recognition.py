"""F0 admission and source-position validation belong to application policy."""
from __future__ import annotations
import hashlib
import math
from typing import Protocol
from ai_worker.domain.advisory import AdvisoryFailure


class InvoiceRecognizer(Protocol):
    def recognize(self, data: bytes) -> dict: ...


class RecognizeInvoice:
    def __init__(self, adapter: InvoiceRecognizer):
        self.adapter = adapter

    def execute(self, document_id: str, data: bytes, checksum: str, page_count: int) -> dict:
        if not data.startswith(b"%PDF-") or hashlib.sha256(data).hexdigest() != checksum:
            raise AdvisoryFailure("SOURCE_MISMATCH")
        if type(page_count) is not int or not 1 <= page_count <= 2 or len(data) > 4_000_000:
            raise AdvisoryFailure("OCR_LIMIT")
        return normalize_ocr(document_id, checksum, page_count, self.adapter.recognize(data))


def normalize_ocr(document_id: str, checksum: str, page_count: int, result: dict) -> dict:
    try:
        return _normalize_ocr(document_id, checksum, page_count, result)
    except (TypeError, ValueError, KeyError, IndexError, AttributeError, RecursionError) as exc:
        raise AdvisoryFailure("OCR_INVALID_RESPONSE") from exc


def _normalize_ocr(document_id: str, checksum: str, page_count: int, result: dict) -> dict:
    def reject():
        raise AdvisoryFailure("OCR_INVALID_RESPONSE")
    if not isinstance(result, dict) or result.get("apiVersion") != "2024-11-30" \
            or result.get("modelId") != "prebuilt-invoice" or result.get("stringIndexType") != "unicodeCodePoint":
        reject()
    content, pages = result.get("content"), result.get("pages")
    if not isinstance(content, str) or len(content.encode()) > 100000 or not isinstance(pages, list) or len(pages) != page_count:
        reject()
    normalized = []
    for number, page in enumerate(pages, 1):
        if not isinstance(page, dict) or page.get("pageNumber") != number:
            reject()
        dimensions = [page.get("width"), page.get("height")]
        if any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0 for v in dimensions):
            reject()
        if page.get("unit") not in {"inch", "pixel"}:
            reject()
        page_spans = page.get("spans")
        if not isinstance(page_spans, list) or not page_spans or len(page_spans) > 10:
            reject()
        for span in page_spans:
            if not isinstance(span, dict) or type(span.get("offset")) is not int or type(span.get("length")) is not int \
                    or span["offset"] < 0 or span["length"] < 0 or span["offset"] + span["length"] > len(content):
                reject()
        lines = page.get("lines", [])
        if not isinstance(lines, list) or len(lines) > 1000:
            reject()
        segments = []
        for line in lines:
            if not isinstance(line, dict) or not isinstance(line.get("content"), str):
                reject()
            spans, polygon = line.get("spans"), line.get("polygon")
            if not isinstance(spans, list) or len(spans) != 1 or not isinstance(spans[0], dict):
                reject()
            start, length = spans[0].get("offset"), spans[0].get("length")
            if type(start) is not int or type(length) is not int or start < 0 or length < 1 \
                    or start + length > len(content) or content[start:start+length] != line["content"] \
                    or not any(p["offset"] <= start and start+length <= p["offset"]+p["length"] for p in page_spans):
                reject()
            if not isinstance(polygon, list) or len(polygon) != 8 or any(type(v) not in (int, float)
                    or not math.isfinite(v) or not 0 <= v <= dimensions[i % 2] for i, v in enumerate(polygon)):
                reject()
            segments.append({"offset": start, "length": length, "text": line["content"], "polygon": polygon})
        normalized.append({"page": number, "width": dimensions[0], "height": dimensions[1],
                           "unit": page["unit"], "spans": page_spans, "segments": segments})
    # Only supported invoice fields, preserving provider content/spans/confidence.
    fields = []
    documents = result.get("documents", [])
    if not isinstance(documents, list) or len(documents) > 10:
        reject()
    for document in documents:
        if not isinstance(document, dict) or not isinstance(document.get("fields"), dict):
            reject()
        def add(name, field):
            if not isinstance(field, dict) or not isinstance(field.get("content"), str):
                return  # Missing fields are candidates absent, never invented values.
            spans, confidence, regions = field.get("spans"), field.get("confidence"), field.get("boundingRegions", [])
            if not isinstance(spans, list) or not spans or len(spans) > 10 \
                    or type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1 \
                    or not isinstance(regions, list) or not regions:
                reject()
            for span in spans:
                if not isinstance(span, dict) or type(span.get("offset")) is not int or type(span.get("length")) is not int \
                        or span["offset"] < 0 or span["length"] < 1 or span["offset"] + span["length"] > len(content):
                    reject()
            raw = " ".join(content[s["offset"]:s["offset"]+s["length"]] for s in spans)
            if " ".join(raw.split()) != " ".join(field["content"].split()):
                reject()
            for region in regions:
                if not isinstance(region, dict) or type(region.get("pageNumber")) is not int \
                        or not 1 <= region["pageNumber"] <= page_count:
                    reject()
                polygon = region.get("polygon")
                page = normalized[region["pageNumber"]-1]
                if not isinstance(polygon, list) or len(polygon) != 8 or any(type(v) not in (int, float)
                        or not math.isfinite(v) or not 0 <= v <= (page["width"], page["height"])[i % 2]
                        for i, v in enumerate(polygon)):
                    reject()
            if any(not any(p["offset"] <= span["offset"] and span["offset"]+span["length"] <= p["offset"]+p["length"]
                    for region in regions for p in normalized[region["pageNumber"]-1]["spans"]) for span in spans):
                reject()
            fields.append({"name": name, "content": field["content"], "spans": spans,
                           "confidence": confidence, "pages": sorted(set(r["pageNumber"] for r in regions))})
        supported = {"InvoiceId", "VendorName", "InvoiceDate", "InvoiceTotal", "CurrencyCode"}
        for name in sorted(supported & document["fields"].keys()):
            add(name, document["fields"][name])
        items = document["fields"].get("Items", {}).get("valueArray", [])
        if not isinstance(items, list) or len(items) > 100:
            reject()
        for index, item in enumerate(items, 1):
            if not isinstance(item, dict) or not isinstance(item.get("valueObject"), dict):
                reject()
            for name in ("Description", "Quantity", "UnitPrice", "Amount"):
                if name in item["valueObject"]:
                    add(f"Items.{index}.{name}", item["valueObject"][name])
    output = {"schemaVersion": "invoice-ocr-v1", "providerVersion": "azure-prebuilt-invoice-2024-11-30",
              "documentId": document_id, "checksum": checksum, "content": content, "pages": normalized, "fields": fields}
    import json
    if len(json.dumps(output, ensure_ascii=False).encode()) > 120000:
        raise AdvisoryFailure("AI_INPUT_LIMIT")
    return output
