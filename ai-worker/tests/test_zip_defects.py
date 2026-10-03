import hashlib
import zipfile

import pytest
from fixtures import (
    XLSX_MEDIA_TYPE,
    CellSpec,
    SheetSpec,
    build_xlsx,
    raw_zip,
    set_zip_entry_compress_size_zero,
    set_zip_entry_flag,
)

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS, ParseLimits
from ai_worker.application.service import ParseRequest
from ai_worker.domain import errors

MIB = 1024 * 1024


def _parse(data, limits=DEFAULT_LIMITS):
    request = ParseRequest(
        document_id="doc",
        media_type=XLSX_MEDIA_TYPE,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )
    return composition.parse_in_process(request, data, limits)


def _minimal_workbook(**kwargs):
    return build_xlsx(
        [SheetSpec(name="Sheet1", cells=[CellSpec("A1", value="1")])], **kwargs
    )


def test_duplicate_zip_entry_rejected():
    data = raw_zip([("[Content_Types].xml", b"x"), ("xl/workbook.xml", b"y"), ("xl/workbook.xml", b"z")])
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_DUPLICATE_ENTRY


def test_path_traversal_entry_rejected():
    data = raw_zip([("[Content_Types].xml", b"x"), ("xl/workbook.xml", b"y"), ("../evil.txt", b"z")])
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_UNSAFE_PATH


def test_absolute_and_backslash_paths_rejected():
    for bad_name in ("/etc/passwd", "..\\evil", "C:/evil"):
        data = raw_zip([("[Content_Types].xml", b"x"), ("xl/workbook.xml", b"y"), (bad_name, b"z")])
        with pytest.raises(errors.ParseFailure) as excinfo:
            _parse(data)
        assert excinfo.value.code == errors.ZIP_UNSAFE_PATH


def test_encrypted_zip_entry_rejected():
    data = set_zip_entry_flag(_minimal_workbook(), "xl/workbook.xml", 0x1)
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_ENCRYPTED_ENTRY


def test_zip_entry_count_limit():
    extra = [(f"junk/entry{i}.bin", b"") for i in range(1001)]
    data = _minimal_workbook(extra_entries=extra)
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_ENTRY_LIMIT


def test_zip_entry_size_limit_streamed():
    data = _minimal_workbook(extra_entries=[("junk/big.bin", b"\x00" * (11 * MIB))])
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_ENTRY_TOO_LARGE


def test_zip_total_size_limit_streamed():
    # Stored (not deflated) so the total-size branch fires instead of ratio.
    limits = ParseLimits(max_zip_total_uncompressed_bytes=1 * MIB)
    data = _minimal_workbook(
        compression=zipfile.ZIP_STORED,
        extra_entries=[
            ("junk/a.bin", b"a" * (700 * 1024)),
            ("junk/b.bin", b"b" * (700 * 1024)),
        ],
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data, limits)
    assert excinfo.value.code == errors.ZIP_TOTAL_TOO_LARGE


def test_compression_ratio_bomb_rejected():
    data = _minimal_workbook(extra_entries=[("junk/bomb.bin", b"\x00" * (5 * MIB))])
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_COMPRESSION_RATIO


def test_sub_mib_compression_ratio_rejected():
    # Previously exempted below 1 MiB; the exception is removed.
    data = _minimal_workbook(extra_entries=[("junk/small.bin", b"\x00" * (256 * 1024))])
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_COMPRESSION_RATIO


def test_nonempty_entry_with_zero_compressed_size_rejected():
    data = set_zip_entry_compress_size_zero(
        _minimal_workbook(compression=zipfile.ZIP_STORED), "xl/workbook.xml"
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.ZIP_COMPRESSION_RATIO


def test_plain_dtd_rejected():
    data = _minimal_workbook(
        entries_override={
            "xl/worksheets/sheet1.xml": (
                '<?xml version="1.0"?>'
                "<!DOCTYPE worksheet>"
                '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                "<sheetData/></worksheet>"
            ).encode("utf-8")
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XML_UNSAFE


def test_entity_expansion_rejected():
    data = _minimal_workbook(
        entries_override={
            "xl/worksheets/sheet1.xml": (
                '<?xml version="1.0"?>'
                '<!DOCTYPE worksheet [<!ENTITY boom "expanded">]>'
                f'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&boom;</t></is></c></row>"
                "</sheetData></worksheet>"
            ).encode("utf-8")
        }
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XML_UNSAFE


def test_ole_encrypted_workbook_rejected():
    data = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 64
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_ENCRYPTED


def test_broken_workbook_rejected():
    data = raw_zip(
        [
            ("[Content_Types].xml", b"<Types/>"),
            ("xl/workbook.xml", b"<not valid xml"),
        ]
    )
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.XLSX_CORRUPT


def test_not_a_zip_spoofing_content_types():
    data = b"PK\x03\x04garbage-not-a-zip"
    with pytest.raises(errors.ParseFailure) as excinfo:
        _parse(data)
    assert excinfo.value.code == errors.FORMAT_UNSUPPORTED
