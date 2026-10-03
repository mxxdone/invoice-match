"""Deterministic mapping from domain results to the ``document-parse-v1`` wire shape.

Pure data mapping: no SDK, process or API imports. Keys are serialized sorted
and no timestamp/random value is ever introduced, so identical input bytes and
metadata produce byte-identical JSON.
"""

from __future__ import annotations

import json

from ai_worker.domain import errors
from ai_worker.domain.result import DocumentParseResult


def bounded_result_to_wire(result: DocumentParseResult, max_bytes: int) -> dict:
    wire = result_to_wire(result)
    payload = json.dumps(
        wire, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    if len(payload) > max_bytes:
        raise errors.ParseFailure(errors.RESULT_TOO_LARGE)
    return wire


def _cell_to_wire(cell) -> dict:
    return {
        "coordinate": cell.coordinate,
        "column": cell.column,
        "type": cell.cell_type,
        "value": cell.value,
        "cachedValue": cell.cached_value,
        "cachedType": cell.cached_type,
        "numberFormat": cell.number_format,
    }


def result_to_wire(result: DocumentParseResult) -> dict:
    wire: dict = {
        "schemaVersion": result.schema_version,
        "parserVersion": result.parser_version,
        "documentId": result.document_id,
        "source": {
            "mediaType": result.source.media_type,
            "sizeBytes": result.source.size_bytes,
            "sha256": result.source.sha256,
        },
        "kind": result.kind,
        "warnings": [
            {"code": warning.code, "message": warning.message, "page": warning.page}
            for warning in result.warnings
        ],
        "pdf": None,
        "xlsx": None,
    }

    if result.pdf is not None:
        wire["pdf"] = {
            "pageCount": result.pdf.page_count,
            "pages": [{"page": page.page, "text": page.text} for page in result.pdf.pages],
        }

    if result.spreadsheet is not None:
        wire["xlsx"] = {
            "sheetCount": result.spreadsheet.sheet_count,
            "sheets": [
                {
                    "index": sheet.index,
                    "name": sheet.name,
                    "state": sheet.state,
                    "rows": [
                        {
                            "row": row.row,
                            "cells": [_cell_to_wire(cell) for cell in row.cells],
                        }
                        for row in sheet.rows
                    ],
                }
                for sheet in result.spreadsheet.sheets
            ],
        }

    return wire
