"""Structural OOXML validation tests.

Each case is a single defect introduced into a valid baseline. Malformed
workbooks must fail with a typed code, never succeed with ``sheetCount == 0``.
"""

import hashlib

import pytest
from fixtures import (
    MAIN_NS,
    PKG_REL_NS,
    REL_NS,
    XLSX_MEDIA_TYPE,
    CellSpec,
    SheetSpec,
    build_xlsx,
)

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS
from ai_worker.application.service import ParseRequest
from ai_worker.domain import errors


def _parse(data):
    request = ParseRequest(
        document_id="doc",
        media_type=XLSX_MEDIA_TYPE,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )
    return composition.parse_in_process(request, data, DEFAULT_LIMITS)


def _baseline(**kwargs):
    return build_xlsx(
        [SheetSpec(name="S", cells=[CellSpec("A1", value="1")])], **kwargs
    )


def _workbook(sheets_xml: str) -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<workbook xmlns="{MAIN_NS}" xmlns:r="{REL_NS}">'
        f"<sheets>{sheets_xml}</sheets></workbook>"
    ).encode("utf-8")


def _rels(rels_xml: str) -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<Relationships xmlns="{PKG_REL_NS}">{rels_xml}</Relationships>'
    ).encode("utf-8")


def _sheet(rows_xml: str) -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<worksheet xmlns="{MAIN_NS}"><sheetData>{rows_xml}</sheetData></worksheet>'
    ).encode("utf-8")


SHEET_REL = (
    f'<Relationship Id="rIdSheet1" Type="{REL_NS}/worksheet" '
    'Target="worksheets/sheet1.xml"/>'
)


def test_empty_cell_still_advances_implicit_column():
    data = _baseline(entries_override={
        "xl/worksheets/sheet1.xml": _sheet('<row r="1"><c r="B1"/><c><v>9</v></c></row>')
    })
    cells = _parse(data).spreadsheet.sheets[0].rows[0].cells
    assert len(cells) == 1
    assert cells[0].coordinate == "C1"
    assert cells[0].column == 3


def test_duplicate_empty_cell_is_corrupt():
    data = _baseline(entries_override={
        "xl/worksheets/sheet1.xml": _sheet('<row r="1"><c r="A1"/><c r="A1"><v>9</v></c></row>')
    })
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_wrong_workbook_root_rejected():
    data = _baseline(
        entries_override={
            "xl/workbook.xml": f'<?xml version="1.0"?><wrong xmlns="{MAIN_NS}"/>'.encode()
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_missing_sheets_element_rejected():
    data = _baseline(
        entries_override={
            "xl/workbook.xml": f'<?xml version="1.0"?><workbook xmlns="{MAIN_NS}"/>'.encode()
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_zero_sheets_rejected():
    data = _baseline(entries_override={"xl/workbook.xml": _workbook("")})
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_sheet_missing_relationship_id_rejected():
    data = _baseline(
        entries_override={"xl/workbook.xml": _workbook('<sheet name="S" sheetId="1"/>')}
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_duplicate_sheet_id_rejected():
    sheets = (
        '<sheet name="S1" sheetId="1" r:id="rIdSheet1"/>'
        '<sheet name="S2" sheetId="1" r:id="rIdSheet1"/>'
    )
    data = _baseline(entries_override={"xl/workbook.xml": _workbook(sheets)})
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_wrong_relationships_root_rejected():
    data = _baseline(
        entries_override={
            "xl/_rels/workbook.xml.rels": f'<?xml version="1.0"?><foo xmlns="{PKG_REL_NS}"/>'.encode()
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_missing_relationship_for_sheet_rejected():
    data = _baseline(
        entries_override={
            "xl/_rels/workbook.xml.rels": _rels(
                '<Relationship Id="rIdOther" Type="' + REL_NS + '/worksheet" '
                'Target="worksheets/sheet1.xml"/>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_duplicate_relationship_id_rejected():
    data = _baseline(
        entries_override={
            "xl/_rels/workbook.xml.rels": _rels(SHEET_REL + SHEET_REL)
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_worksheet_relationship_with_wrong_type_rejected():
    data = _baseline(
        entries_override={
            "xl/_rels/workbook.xml.rels": _rels(
                '<Relationship Id="rIdSheet1" Type="' + REL_NS + '/sharedStrings" '
                'Target="worksheets/sheet1.xml"/>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_external_worksheet_target_rejected():
    data = _baseline(
        entries_override={
            "xl/_rels/workbook.xml.rels": _rels(
                '<Relationship Id="rIdSheet1" Type="' + REL_NS + '/worksheet" '
                'Target="https://example.test/sheet1.xml" TargetMode="External"/>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_relationship_target_traversal_rejected():
    data = _baseline(
        entries_override={
            "xl/_rels/workbook.xml.rels": _rels(
                '<Relationship Id="rIdSheet1" Type="' + REL_NS + '/worksheet" '
                'Target="../../evil.xml"/>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_relationship_missing_target_rejected():
    data = _baseline(
        entries_override={
            "xl/_rels/workbook.xml.rels": _rels(
                '<Relationship Id="rIdSheet1" Type="' + REL_NS + '/worksheet"/>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_negative_shared_string_index_rejected():
    data = build_xlsx(
        [SheetSpec(name="S", cells=[CellSpec("A1", kind="s", value="-1")])],
        shared_strings=["x"],
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_out_of_range_shared_string_index_rejected():
    data = build_xlsx(
        [SheetSpec(name="S", cells=[CellSpec("A1", kind="s", value="5")])],
        shared_strings=["x"],
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_invalid_row_number_rejected():
    data = _baseline(
        entries_override={
            "xl/worksheets/sheet1.xml": _sheet(
                '<row r="abc"><c r="A1"><v>1</v></c></row>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_duplicate_row_rejected():
    data = _baseline(
        entries_override={
            "xl/worksheets/sheet1.xml": _sheet(
                '<row r="1"><c r="A1"><v>1</v></c></row>'
                '<row r="1"><c r="B1"><v>2</v></c></row>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_out_of_order_row_rejected():
    data = _baseline(
        entries_override={
            "xl/worksheets/sheet1.xml": _sheet(
                '<row r="2"><c r="A2"><v>2</v></c></row>'
                '<row r="1"><c r="A1"><v>1</v></c></row>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_cell_reference_row_mismatch_rejected():
    data = _baseline(
        entries_override={
            "xl/worksheets/sheet1.xml": _sheet(
                '<row r="1"><c r="A2"><v>1</v></c></row>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_duplicate_cell_reference_rejected():
    data = _baseline(
        entries_override={
            "xl/worksheets/sheet1.xml": _sheet(
                '<row r="1"><c r="A1"><v>1</v></c><c r="A1"><v>2</v></c></row>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_out_of_order_cell_column_rejected():
    data = _baseline(
        entries_override={
            "xl/worksheets/sheet1.xml": _sheet(
                '<row r="1"><c r="B1"><v>2</v></c><c r="A1"><v>1</v></c></row>'
            )
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_unknown_cell_type_rejected():
    data = build_xlsx(
        [SheetSpec(name="S", cells=[CellSpec("A1", kind="zz", value="1")])]
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_invalid_boolean_rejected():
    data = build_xlsx([SheetSpec(name="S", cells=[CellSpec("A1", kind="b", value="2")])])
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_iso_date_cell_is_preserved_as_date():
    iso = "2023-12-31T00:00:00"
    data = build_xlsx([SheetSpec(name="S", cells=[CellSpec("A1", kind="d", value=iso)])])
    result = _parse(data)
    cell = result.spreadsheet.sheets[0].rows[0].cells[0]
    assert cell.cell_type == "date"
    assert cell.value == iso
