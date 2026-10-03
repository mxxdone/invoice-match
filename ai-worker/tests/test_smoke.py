import hashlib
import json

from fixtures import (
    PDF_MEDIA_TYPE,
    XLSX_MEDIA_TYPE,
    CellSpec,
    SheetSpec,
    build_pdf,
    build_xlsx,
)

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS
from ai_worker.application.service import ParseRequest
from ai_worker.application.wire import result_to_wire


def _request(document_id, media_type, data):
    return ParseRequest(
        document_id=document_id,
        media_type=media_type,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def test_pdf_smoke():
    data = build_pdf(["가나다"])
    result = composition.parse_in_process(
        _request("doc-1", PDF_MEDIA_TYPE, data), data
    )
    assert result.kind == "pdf"
    assert result.pdf.page_count == 1
    assert result.pdf.pages[0].text == "가나다"
    wire = result_to_wire(result)
    assert json.dumps(wire)  # JSON safe


def test_xlsx_smoke():
    sheets = [
        SheetSpec(
            name="매출",
            cells=[
                CellSpec("A1", kind="s", value="0"),
                CellSpec("B1", value="12345678901234567890"),
                CellSpec("C1", value="0.1"),
                CellSpec("D1", value="0.2"),
            ],
        )
    ]
    data = build_xlsx(sheets, shared_strings=["매출"])
    result = composition.parse_in_process(
        _request("doc-2", XLSX_MEDIA_TYPE, data), data
    )
    assert result.kind == "xlsx"
    sheet = result.spreadsheet.sheets[0]
    assert sheet.name == "매출"
    cells = {cell.coordinate: cell for row in sheet.rows for cell in row.cells}
    assert cells["A1"].value == "매출"
    assert cells["B1"].value == "12345678901234567890"
    assert cells["C1"].value == "0.1"
