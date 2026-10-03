import hashlib
import zipfile

import pytest
from fixtures import (
    PDF_MEDIA_TYPE,
    XLSX_MEDIA_TYPE,
    CellSpec,
    SheetSpec,
    build_pdf,
    build_xlsx,
)

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS, ParseLimits
from ai_worker.application.service import ParseRequest
from ai_worker.domain import errors


def _request(data, media_type):
    return ParseRequest(
        document_id="doc",
        media_type=media_type,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def test_sheet_limit_default():
    sheets = [
        SheetSpec(name=f"S{index}", cells=[CellSpec("A1", value="1")])
        for index in range(21)
    ]
    data = build_xlsx(sheets)
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, XLSX_MEDIA_TYPE), data)
    assert excinfo.value.code == errors.XLSX_SHEET_LIMIT


def test_dimension_bypass_by_large_row_rejected():
    sheet = SheetSpec(
        name="S",
        dimension="A1:A2",
        cells=[CellSpec("A1", value="1"), CellSpec("A10001", value="1")],
    )
    data = build_xlsx([sheet])
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, XLSX_MEDIA_TYPE), data)
    assert excinfo.value.code == errors.XLSX_DIMENSION_LIMIT


def test_dimension_bypass_by_large_column_rejected():
    sheet = SheetSpec(
        name="S",
        dimension="A1:A2",
        cells=[CellSpec("A1", value="1"), CellSpec("IX1", value="1")],
    )
    data = build_xlsx([sheet])
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, XLSX_MEDIA_TYPE), data)
    assert excinfo.value.code == errors.XLSX_DIMENSION_LIMIT


def test_nonempty_cell_limit_override():
    limits = ParseLimits(max_nonempty_cells=5)
    cells = [CellSpec(f"A{row}", value="1") for row in range(1, 7)]
    data = build_xlsx([SheetSpec(name="S", cells=cells)])
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, XLSX_MEDIA_TYPE), data, limits)
    assert excinfo.value.code == errors.XLSX_CELL_LIMIT


def _column_letters(index: int) -> str:
    letters = ""
    while index > 0:
        index, remainder = divmod(index - 1, 26)
        letters = chr(ord("A") + remainder) + letters
    return letters


def test_nonempty_cell_limit_default():
    cells = []
    total = 0
    for row in range(1, 402):
        for column in range(1, 251):
            cells.append(CellSpec(f"{_column_letters(column)}{row}", value="1"))
            total += 1
            if total > DEFAULT_LIMITS.max_nonempty_cells:
                break
        if total > DEFAULT_LIMITS.max_nonempty_cells:
            break
    assert total == DEFAULT_LIMITS.max_nonempty_cells + 1
    # Stored so the cell-count branch fires instead of the compression ratio.
    data = build_xlsx([SheetSpec(name="S", cells=cells)], compression=zipfile.ZIP_STORED)
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, XLSX_MEDIA_TYPE), data)
    assert excinfo.value.code == errors.XLSX_CELL_LIMIT


def test_text_value_limit_override_xlsx():
    limits = ParseLimits(max_text_value_bytes=5)
    data = build_xlsx([SheetSpec(name="S", cells=[CellSpec("A1", inline="hello world")])])
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, XLSX_MEDIA_TYPE), data, limits)
    assert excinfo.value.code == errors.TEXT_VALUE_LIMIT


def test_text_value_limit_override_pdf():
    limits = ParseLimits(max_text_value_bytes=2)
    data = build_pdf(["가나다"])
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, PDF_MEDIA_TYPE), data, limits)
    assert excinfo.value.code == errors.TEXT_VALUE_LIMIT


def test_result_json_limit_enforced_in_child_logic():
    limits = ParseLimits(max_result_json_bytes=64)
    data = build_pdf(["가나다"])
    request = _request(data, PDF_MEDIA_TYPE)
    header = {
        "documentId": "doc",
        "mediaType": request.media_type,
        "sizeBytes": request.size_bytes,
        "sha256": request.sha256,
        "limits": limits.to_wire(),
    }
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.run_child_parse(header, data)
    assert excinfo.value.code == errors.RESULT_TOO_LARGE
