import hashlib
import json

from fixtures import XLSX_MEDIA_TYPE, CellSpec, SheetSpec, build_xlsx

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS
from ai_worker.application.service import ParseRequest
from ai_worker.application.wire import result_to_wire


def _request(document_id, data, media_type=XLSX_MEDIA_TYPE):
    return ParseRequest(
        document_id=document_id,
        media_type=media_type,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def _rich_workbook():
    sheets = [
        SheetSpec(
            name="매출",
            cells=[
                CellSpec("A1", kind="s", value="0"),
                CellSpec("B1", kind="s", value="1"),
                CellSpec("A2", kind="s", value="2"),
                CellSpec("B2", value="12000"),
                CellSpec("C2", value="0.30000000000000004"),
                CellSpec("D2", formula="SUM(B2:B2)", value="12000"),
                CellSpec("E2", kind="b", value="1"),
                CellSpec("F2", kind="e", value="#DIV/0!"),
                CellSpec("G2", value="45291", style=1),
                CellSpec("H2", value="45291", style=2),
                CellSpec("I2", value="3.5", style=3),
                CellSpec("Z100", value="7"),
            ],
        ),
        SheetSpec(name="메모", state="hidden", cells=[CellSpec("A1", inline="비고")]),
    ]
    return build_xlsx(
        sheets,
        shared_strings=["품목", "수량", "사과"],
        style_num_format_ids=[0, 14, 165, 164],
        style_custom_formats={165: "yyyy-mm-dd", 164: "0.00"},
    )


def test_workbook_order_names_and_state():
    data = _rich_workbook()
    result = composition.parse_in_process(_request("doc", data), data)
    assert result.kind == "xlsx"
    assert [sheet.index for sheet in result.spreadsheet.sheets] == [1, 2]
    assert [sheet.name for sheet in result.spreadsheet.sheets] == ["매출", "메모"]
    assert result.spreadsheet.sheets[0].state == "visible"
    assert result.spreadsheet.sheets[1].state == "hidden"


def test_cell_values_types_and_original_lexemes():
    data = _rich_workbook()
    result = composition.parse_in_process(_request("doc", data), data)
    cells = {
        cell.coordinate: cell
        for row in result.spreadsheet.sheets[0].rows
        for cell in row.cells
    }
    assert cells["A1"].value == "품목"
    assert cells["A1"].cell_type == "string"
    assert cells["B2"].cell_type == "number"
    assert cells["B2"].value == "12000"
    assert cells["C2"].value == "0.30000000000000004"
    assert cells["E2"].cell_type == "boolean"
    assert cells["E2"].value is True
    assert cells["F2"].cell_type == "error"
    assert cells["F2"].value == "#DIV/0!"
    assert cells["G2"].cell_type == "date"
    assert cells["G2"].value == "45291"
    assert cells["G2"].number_format == "14"
    assert cells["H2"].cell_type == "date"
    assert cells["H2"].number_format == "yyyy-mm-dd"
    assert cells["I2"].cell_type == "number"
    assert cells["I2"].number_format is None


def test_formula_expression_and_cache_are_distinguished():
    data = _rich_workbook()
    result = composition.parse_in_process(_request("doc", data), data)
    cells = {
        cell.coordinate: cell
        for row in result.spreadsheet.sheets[0].rows
        for cell in row.cells
    }
    formula = cells["D2"]
    assert formula.cell_type == "formula"
    assert formula.value == "=SUM(B2:B2)"
    assert formula.cached_value == "12000"
    assert formula.cached_type == "number"


def test_sparse_cells_keep_coordinates_and_only_nonempty_rows():
    data = _rich_workbook()
    result = composition.parse_in_process(_request("doc", data), data)
    sheet = result.spreadsheet.sheets[0]
    assert [row.row for row in sheet.rows] == [1, 2, 100]
    sparse = sheet.rows[-1].cells[0]
    assert sparse.coordinate == "Z100"
    assert sparse.column == 26
    assert sparse.row == 100


def test_inline_string_on_hidden_sheet():
    data = _rich_workbook()
    result = composition.parse_in_process(_request("doc", data), data)
    sheet = result.spreadsheet.sheets[1]
    assert sheet.rows[0].cells[0].value == "비고"
    assert sheet.rows[0].cells[0].cell_type == "string"


def test_result_is_deterministic():
    data = _rich_workbook()
    request = _request("doc-7", data)
    first = result_to_wire(composition.parse_in_process(request, data))
    second = result_to_wire(composition.parse_in_process(request, data))
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)
